package com.funding.graphql;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpSyncGraphQlClient;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end: real HTTP -> Spring GraphQL -> JDBC -> throwaway Postgres 18 (Testcontainers)
 * built from the same schema as funding-db. Seed data is reset before every test.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Sql(scripts = "/db/02-seed.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class FundingGraphqlE2ETest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18")
            .withDatabaseName("auto_funding_db")
            .withCopyFileToContainer(MountableFile.forClasspathResource("db/01-schema.sql"),
                    "/docker-entrypoint-initdb.d/01-schema.sql");

    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;

    HttpSyncGraphQlClient client;

    @BeforeEach
    void setUp() {
        client = HttpSyncGraphQlClient.create(RestClient.create("http://localhost:" + port + "/graphql"));
    }

    // ------------------------------------------------------------------ queries

    @Test
    void listsApplicationsFilteredByStatusNewestFirst() {
        List<String> ids = client.document("{ applications(status: READY_TO_FUND) { applicationId } }")
                .retrieveSync("applications").toEntityList(Map.class)
                .stream().map(m -> (String) m.get("applicationId")).toList();

        assertThat(ids).containsExactly("app_1", "app_3");
    }

    @Test
    void filtersApplicationsByDealerAndPaginates() {
        List<String> ids = client.document("{ applications(dealerId: \"dlr_1\", limit: 1, offset: 1) { applicationId } }")
                .retrieveSync("applications").toEntityList(Map.class)
                .stream().map(m -> (String) m.get("applicationId")).toList();

        assertThat(ids).containsExactly("app_2");
    }

    @Test
    void resolvesNestedApplicationGraph() {
        ClientGraphQlResponse res = client.document("""
                {
                  application(applicationId: "app_1") {
                    dealNumber
                    dealer { legalName }
                    applicant { fullName ficoScore dateOfBirth }
                    vehicle { vin condition }
                    loanTerms { amountFinanced apr termMonths }
                    tasks { taskId status sourceComparison }
                    pending: tasks(status: PENDING_REVIEW) { taskId }
                    riscLineItems { lineItemId bucket amount }
                  }
                }""").executeSync();

        assertThat(res.getErrors()).isEmpty();
        assertThat(val(res, "application.dealNumber")).isEqualTo("D-001");
        assertThat(val(res, "application.dealer.legalName")).isEqualTo("Test Motors LLC");
        assertThat(val(res, "application.applicant.fullName")).isEqualTo("Jane Tester");
        assertThat(val(res, "application.applicant.dateOfBirth")).isEqualTo("1990-01-15");
        assertThat(val(res, "application.vehicle.condition")).isEqualTo("NEW");
        assertThat(val(res, "application.loanTerms.termMonths")).isEqualTo(60);
        assertThat(res.field("application.tasks").toEntityList(Map.class)).hasSize(2);
        assertThat(res.field("application.tasks[0].sourceComparison").toEntity(Map.class))
                .containsEntry("fieldName", "vin").containsEntry("isMatch", true);
        assertThat(res.field("application.pending").toEntityList(Map.class)
                .stream().map(m -> m.get("taskId")).toList()).containsExactly("task_1b");
        assertThat(res.field("application.riscLineItems").toEntityList(Map.class)).hasSize(2);
    }

    @Test
    void unknownApplicationReturnsNull() {
        ClientGraphQlResponse res = client.document("{ application(applicationId: \"nope\") { applicationId } }")
                .executeSync();

        assertThat(res.getErrors()).isEmpty();
        assertThat(val(res, "application")).isNull();
    }

    private static Object val(ClientGraphQlResponse res, String path) {
        return res.field(path).getValue();
    }

    // ---------------------------------------------------------------- mutations

    @Test
    void fundingAnApplicationSetsFundedAtAndLogsActivity() {
        ClientGraphQlResponse res = client.document("""
                mutation {
                  updateApplicationStatus(input: {applicationId: "app_1", status: FUNDED, actor: "T. Funder"}) {
                    status fundedAt
                    activity(limit: 1) { actor role action details metadata }
                  }
                }""").executeSync();

        assertThat(res.getErrors()).isEmpty();
        assertThat(val(res, "updateApplicationStatus.status")).isEqualTo("FUNDED");
        assertThat(val(res, "updateApplicationStatus.fundedAt")).isNotNull();
        assertThat(val(res, "updateApplicationStatus.activity[0].action")).isEqualTo("STATUS_CHANGED");
        assertThat(val(res, "updateApplicationStatus.activity[0].role")).isEqualTo("FUNDER");
        assertThat(val(res, "updateApplicationStatus.activity[0].details"))
                .isEqualTo("READY_TO_FUND -> FUNDED");
        assertThat(res.field("updateApplicationStatus.activity[0].metadata").toEntity(Map.class))
                .containsEntry("from", "READY_TO_FUND").containsEntry("to", "FUNDED");

        // and it is really persisted
        assertThat(jdbc.sql("SELECT status::text FROM applications WHERE application_id = 'app_1'")
                .query(String.class).single()).isEqualTo("FUNDED");
    }

    @Test
    void verifyingTaskSetsVerifiedByAndLogsTaskEvent() {
        ClientGraphQlResponse res = client.document("""
                mutation {
                  updateFundingTaskStatus(input: {taskId: "task_1b", status: VERIFIED, actor: "T. Funder", details: "paystub ok"}) {
                    taskId status verifiedBy
                  }
                }""").executeSync();

        assertThat(res.getErrors()).isEmpty();
        assertThat(val(res, "updateFundingTaskStatus.status")).isEqualTo("VERIFIED");
        assertThat(val(res, "updateFundingTaskStatus.verifiedBy")).isEqualTo("T. Funder");

        Map<String, Object> event = jdbc.sql("""
                        SELECT task_id, action, details FROM activity_events WHERE application_id = 'app_1'""")
                .query().singleRow();
        assertThat(event).containsEntry("task_id", "task_1b")
                .containsEntry("action", "TASK_STATUS_CHANGED")
                .containsEntry("details", "paystub ok");
    }

    @Test
    void flaggingTaskKeepsPreviousVerifier() {
        String verifiedBy = client.document("""
                        mutation {
                          updateFundingTaskStatus(input: {taskId: "task_1a", status: DISCREPANCY_FLAGGED, actor: "T. Funder"}) {
                            verifiedBy
                          }
                        }""")
                .retrieveSync("updateFundingTaskStatus.verifiedBy").toEntity(String.class);

        assertThat(verifiedBy).isEqualTo("Test Engine");
    }

    @Test
    void addActivityEventStoresJsonMetadata() {
        ClientGraphQlResponse res = client.document("""
                mutation {
                  addActivityEvent(input: {applicationId: "app_2", actor: "Test Engine", action: "NOTE",
                                           details: "called dealer", metadata: {channel: "phone", attempts: 2}}) {
                    eventId role metadata
                  }
                }""").executeSync();

        assertThat(res.getErrors()).isEmpty();
        assertThat(val(res, "addActivityEvent.eventId").toString()).startsWith("evt_");
        assertThat(val(res, "addActivityEvent.role")).isEqualTo("SYSTEM");
        assertThat(res.field("addActivityEvent.metadata").toEntity(Map.class))
                .containsEntry("channel", "phone").containsEntry("attempts", 2);
    }

    // ------------------------------------------------------------------- errors

    @Test
    void unknownActorIsBadRequestAndRollsBackStatusChange() {
        ClientGraphQlResponse res = client.document("""
                mutation {
                  updateApplicationStatus(input: {applicationId: "app_2", status: DECLINED, actor: "Ghost"}) { status }
                }""").executeSync();

        assertThat(res.getErrors()).singleElement().satisfies(e -> {
            assertThat(e.getMessage()).isEqualTo("Unknown actor: Ghost");
            assertThat(e.getErrorType().toString()).isEqualTo("BAD_REQUEST");
        });
        // the UPDATE ran before the event insert failed: it must be rolled back
        assertThat(jdbc.sql("SELECT status::text FROM applications WHERE application_id = 'app_2'")
                .query(String.class).single()).isEqualTo("PENDING_STIPULATIONS");
        assertThat(jdbc.sql("SELECT count(*) FROM activity_events").query(Integer.class).single()).isZero();
    }

    @Test
    void unknownTaskIsBadRequest() {
        ClientGraphQlResponse res = client.document("""
                mutation {
                  updateFundingTaskStatus(input: {taskId: "missing", status: WAIVED, actor: "T. Funder"}) { taskId }
                }""").executeSync();

        assertThat(res.getErrors()).singleElement()
                .satisfies(e -> assertThat(e.getMessage()).isEqualTo("Task not found: missing"));
    }

    @Test
    void invalidEnumIsRejectedBySchemaValidation() {
        ClientGraphQlResponse res = client.document("{ applications(status: BOGUS) { applicationId } }")
                .executeSync();

        assertThat(res.getErrors()).singleElement()
                .satisfies(e -> assertThat(e.getErrorType().toString()).isEqualTo("ValidationError"));
    }
}
