package com.funding.graphql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.funding.graphql.Models.*;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;

@Repository
public class FundingRepository {

    private static final String APP_COLS = """
            application_id, deal_number, dealer_id, dealer_name_snapshot, state_code, submitted_at,
            sla_minutes_remaining, sla_cutoff_at, expires_at, status, funded_at, updated_at""";
    private static final String TASK_COLS = """
            task_id, application_id, category, title, description, status, verified_by,
            source_comparison::text, ocr_evidence::text, stipulation::text, updated_at""";
    private static final String EVENT_COLS = """
            event_id, application_id, occurred_at, task_id, actor, role, action, details, metadata::text""";

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final RowMapper<FundingTask> taskMapper = this::mapTask;
    private final RowMapper<ActivityEvent> eventMapper = this::mapEvent;

    public FundingRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ------------------------------------------------------------------ queries

    public List<Application> findApplications(ApplicationFilter f, int limit, int offset) {
        // A condition is added only for a filter that is set; values are always bound, never concatenated.
        List<String> where = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();

        Set<String> statuses = null;
        if (f.statuses() != null && !f.statuses().isEmpty()) {
            statuses = new LinkedHashSet<>(f.statuses());
            if (f.status() != null) statuses.retainAll(Set.of(f.status()));   // AND with status
            if (statuses.isEmpty()) return List.of();
        } else if (f.status() != null) {
            statuses = Set.of(f.status());
        }
        if (statuses != null) {
            where.add("status::text IN (:statuses)");
            params.put("statuses", statuses);
        }
        if (f.dealerId() != null) {
            where.add("dealer_id = :dealerId");
            params.put("dealerId", f.dealerId());
        }
        if (f.search() != null && !f.search().isBlank()) {
            String escaped = f.search().trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            where.add("(application_id ILIKE :search OR deal_number ILIKE :search)");
            params.put("search", "%" + escaped + "%");
        }
        if (f.taskCategory() != null || f.taskStatus() != null) {
            where.add("""
                    EXISTS (SELECT 1 FROM funding_tasks t
                             WHERE t.application_id = applications.application_id
                               AND (CAST(:taskCategory AS funding_task_category) IS NULL OR t.category = CAST(:taskCategory AS funding_task_category))
                               AND (CAST(:taskStatus AS funding_task_status) IS NULL OR t.status = CAST(:taskStatus AS funding_task_status)))""");
            params.put("taskCategory", f.taskCategory());
            params.put("taskStatus", f.taskStatus());
        }
        params.put("limit", Math.min(Math.max(limit, 1), 200));
        params.put("offset", Math.max(offset, 0));

        String sql = "SELECT " + APP_COLS + " FROM applications"
                + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where))
                + " ORDER BY submitted_at DESC LIMIT :limit OFFSET :offset";
        return jdbc.sql(sql).params(params).query(Application.class).list();
    }

    public Optional<Application> findApplication(String applicationId) {
        return jdbc.sql("SELECT " + APP_COLS + " FROM applications WHERE application_id = :id")
                .param("id", applicationId).query(Application.class).optional();
    }

    public Optional<FundingTask> findTask(String taskId) {
        return jdbc.sql("SELECT " + TASK_COLS + " FROM funding_tasks WHERE task_id = :id")
                .param("id", taskId).query(taskMapper).optional();
    }

    public List<Dealer> findDealers() {
        return jdbc.sql("SELECT dealer_id, legal_name, dba_name, elt_lienholder_code, state_code FROM dealers ORDER BY legal_name")
                .query(Dealer.class).list();
    }

    public List<Actor> findActors() {
        return jdbc.sql("SELECT actor_name, role FROM actors ORDER BY actor_name").query(Actor.class).list();
    }

    // ------------------------------------------------- batch loads (avoid N+1)

    public Map<String, Dealer> dealersByIds(Collection<String> ids) {
        return index(jdbc.sql("SELECT dealer_id, legal_name, dba_name, elt_lienholder_code, state_code FROM dealers WHERE dealer_id IN (:ids)")
                .param("ids", ids).query(Dealer.class).list(), Dealer::dealerId);
    }

    public Map<String, Applicant> applicantsByAppIds(Collection<String> ids) {
        return index(jdbc.sql("SELECT * FROM applicants WHERE application_id IN (:ids)")
                .param("ids", ids).query(new DataClassRowMapper<>(Applicant.class)).list(), Applicant::applicationId);
    }

    public Map<String, Vehicle> vehiclesByAppIds(Collection<String> ids) {
        return index(jdbc.sql("SELECT * FROM vehicles WHERE application_id IN (:ids)")
                .param("ids", ids).query(new DataClassRowMapper<>(Vehicle.class)).list(), Vehicle::applicationId);
    }

    public Map<String, LoanTerms> loanTermsByAppIds(Collection<String> ids) {
        return index(jdbc.sql("SELECT * FROM loan_terms WHERE application_id IN (:ids)")
                .param("ids", ids).query(new DataClassRowMapper<>(LoanTerms.class)).list(), LoanTerms::applicationId);
    }

    public Map<String, List<FundingTask>> tasksByAppIds(Collection<String> ids) {
        return group(jdbc.sql("SELECT " + TASK_COLS + " FROM funding_tasks WHERE application_id IN (:ids) ORDER BY task_id")
                .param("ids", ids).query(taskMapper).list(), FundingTask::applicationId);
    }

    public Map<String, List<RiscLineItem>> riscItemsByAppIds(Collection<String> ids) {
        return group(jdbc.sql("""
                        SELECT * FROM risc_line_items WHERE application_id IN (:ids)
                        ORDER BY bucket, line_order""")
                .param("ids", ids).query(new DataClassRowMapper<>(RiscLineItem.class)).list(), RiscLineItem::applicationId);
    }

    public List<ActivityEvent> activityForApp(String applicationId, int limit) {
        return jdbc.sql("SELECT " + EVENT_COLS + """
                         FROM activity_events WHERE application_id = :id
                        ORDER BY occurred_at DESC LIMIT :limit""")
                .param("id", applicationId).param("limit", Math.min(Math.max(limit, 1), 500))
                .query(eventMapper).list();
    }

    // ---------------------------------------------------------------- mutations

    public boolean updateApplicationStatus(String applicationId, String status) {
        return jdbc.sql("""
                        UPDATE applications
                           SET status = CAST(:status AS application_funding_status),
                               funded_at = CASE WHEN CAST(:status AS application_funding_status) = 'FUNDED'
                                                THEN COALESCE(funded_at, now()) ELSE funded_at END
                         WHERE application_id = :id""")
                .param("status", status).param("id", applicationId).update() == 1;
    }

    public boolean updateTaskStatus(String taskId, String status, String verifiedBy) {
        return jdbc.sql("""
                        UPDATE funding_tasks
                           SET status = CAST(:status AS funding_task_status),
                               verified_by = CASE WHEN CAST(:status AS funding_task_status) IN ('VERIFIED','WAIVED')
                                                  THEN :actor ELSE verified_by END
                         WHERE task_id = :id""")
                .param("status", status).param("actor", verifiedBy).param("id", taskId).update() == 1;
    }

    /** Inserts an event; the actor's role is looked up from the actors table (FK enforces the actor exists). */
    public ActivityEvent insertEvent(String applicationId, String taskId, String actor, String action,
                                     String details, Object metadata) {
        String id = "evt_" + UUID.randomUUID();
        return jdbc.sql("""
                        INSERT INTO activity_events (event_id, application_id, task_id, actor, role, action, details, metadata)
                        SELECT :eventId, :appId, :taskId, a.actor_name, a.role, :action, :details, CAST(:metadata AS jsonb)
                          FROM actors a WHERE a.actor_name = :actor
                        RETURNING """ + " " + EVENT_COLS)
                .param("eventId", id).param("appId", applicationId).param("taskId", taskId)
                .param("actor", actor).param("action", action).param("details", details)
                .param("metadata", toJson(metadata))
                .query(eventMapper).optional()
                .orElseThrow(() -> new IllegalArgumentException("Unknown actor: " + actor));
    }

    // ------------------------------------------------------------------ helpers

    private FundingTask mapTask(ResultSet rs, int n) throws SQLException {
        return new FundingTask(rs.getString("task_id"), rs.getString("application_id"), rs.getString("category"),
                rs.getString("title"), rs.getString("description"), rs.getString("status"),
                rs.getString("verified_by"), parse(rs.getString("source_comparison")),
                parse(rs.getString("ocr_evidence")), parse(rs.getString("stipulation")),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private ActivityEvent mapEvent(ResultSet rs, int n) throws SQLException {
        return new ActivityEvent(rs.getString("event_id"), rs.getString("application_id"),
                rs.getObject("occurred_at", OffsetDateTime.class), rs.getString("task_id"), rs.getString("actor"),
                rs.getString("role"), rs.getString("action"), rs.getString("details"),
                parse(rs.getString("metadata")));
    }

    private Object parse(String s) {
        if (s == null) return null;
        try {
            return json.readValue(s, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Bad JSON in database", e);
        }
    }

    private String toJson(Object o) {
        if (o == null) return null;
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("metadata is not serialisable", e);
        }
    }

    private static <T> Map<String, T> index(List<T> rows, java.util.function.Function<T, String> key) {
        Map<String, T> m = new HashMap<>();
        rows.forEach(r -> m.put(key.apply(r), r));
        return m;
    }

    private static <T> Map<String, List<T>> group(List<T> rows, java.util.function.Function<T, String> key) {
        Map<String, List<T>> m = new LinkedHashMap<>();
        rows.forEach(r -> m.computeIfAbsent(key.apply(r), k -> new ArrayList<>()).add(r));
        return m;
    }
}
