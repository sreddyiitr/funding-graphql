package com.funding.graphql;

import com.funding.graphql.Models.*;
import org.springframework.graphql.data.method.annotation.*;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;

@Controller
public class FundingController {

    private final FundingRepository repo;

    public FundingController(FundingRepository repo) {
        this.repo = repo;
    }

    // ------------------------------------------------------------------ Query

    @QueryMapping
    public List<Application> applications(@Argument String status, @Argument List<String> statuses,
                                          @Argument String dealerId, @Argument String search,
                                          @Argument String taskCategory, @Argument String taskStatus,
                                          @Argument int limit, @Argument int offset) {
        var filter = new ApplicationFilter(status, statuses, dealerId, search, taskCategory, taskStatus);
        return repo.findApplications(filter, limit, offset);
    }

    @QueryMapping
    public Application application(@Argument String applicationId) {
        return repo.findApplication(applicationId).orElse(null);
    }

    @QueryMapping
    public FundingTask fundingTask(@Argument String taskId) {
        return repo.findTask(taskId).orElse(null);
    }

    @QueryMapping
    public List<Dealer> dealers() {
        return repo.findDealers();
    }

    @QueryMapping
    public List<Actor> actors() {
        return repo.findActors();
    }

    // ------------------------------------------- Application nested fields
    // @BatchMapping = one SQL query per field per request, not one per application.

    @BatchMapping
    public Map<Application, Dealer> dealer(List<Application> apps) {
        Map<String, Dealer> byId = repo.dealersByIds(ids(apps, Application::dealerId));
        return remap(apps, a -> byId.get(a.dealerId()));
    }

    @BatchMapping
    public Map<Application, Applicant> applicant(List<Application> apps) {
        Map<String, Applicant> byId = repo.applicantsByAppIds(ids(apps, Application::applicationId));
        return remap(apps, a -> byId.get(a.applicationId()));
    }

    @BatchMapping
    public Map<Application, Vehicle> vehicle(List<Application> apps) {
        Map<String, Vehicle> byId = repo.vehiclesByAppIds(ids(apps, Application::applicationId));
        return remap(apps, a -> byId.get(a.applicationId()));
    }

    @BatchMapping
    public Map<Application, LoanTerms> loanTerms(List<Application> apps) {
        Map<String, LoanTerms> byId = repo.loanTermsByAppIds(ids(apps, Application::applicationId));
        return remap(apps, a -> byId.get(a.applicationId()));
    }

    @BatchMapping
    public Map<Application, List<RiscLineItem>> riscLineItems(List<Application> apps) {
        Map<String, List<RiscLineItem>> byId = repo.riscItemsByAppIds(ids(apps, Application::applicationId));
        return remap(apps, a -> byId.getOrDefault(a.applicationId(), List.of()));
    }

    // tasks/activity take arguments, so they're plain schema mappings.
    @SchemaMapping(typeName = "Application")
    public List<FundingTask> tasks(Application app, @Argument String status) {
        List<FundingTask> all = repo.tasksByAppIds(List.of(app.applicationId()))
                .getOrDefault(app.applicationId(), List.of());
        return status == null ? all : all.stream().filter(t -> t.status().equals(status)).toList();
    }

    @SchemaMapping(typeName = "Application")
    public List<ActivityEvent> activity(Application app, @Argument int limit) {
        return repo.activityForApp(app.applicationId(), limit);
    }

    // --------------------------------------------------------------- Mutation

    @MutationMapping
    @Transactional
    public Application updateApplicationStatus(@Argument UpdateApplicationStatusInput input) {
        Application before = repo.findApplication(input.applicationId())
                .orElseThrow(() -> new IllegalArgumentException("Application not found: " + input.applicationId()));
        repo.updateApplicationStatus(input.applicationId(), input.status());
        repo.insertEvent(input.applicationId(), null, input.actor(), "STATUS_CHANGED",
                input.details() != null ? input.details() : before.status() + " -> " + input.status(),
                Map.of("from", before.status(), "to", input.status()));
        return repo.findApplication(input.applicationId()).orElseThrow();
    }

    @MutationMapping
    @Transactional
    public FundingTask updateFundingTaskStatus(@Argument UpdateFundingTaskStatusInput input) {
        FundingTask before = repo.findTask(input.taskId())
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + input.taskId()));
        repo.updateTaskStatus(input.taskId(), input.status(), input.actor());
        repo.insertEvent(before.applicationId(), input.taskId(), input.actor(), "TASK_STATUS_CHANGED",
                input.details() != null ? input.details() : before.status() + " -> " + input.status(),
                Map.of("from", before.status(), "to", input.status()));
        return repo.findTask(input.taskId()).orElseThrow();
    }

    @MutationMapping
    @Transactional
    public ActivityEvent addActivityEvent(@Argument AddActivityEventInput input) {
        repo.findApplication(input.applicationId())
                .orElseThrow(() -> new IllegalArgumentException("Application not found: " + input.applicationId()));
        return repo.insertEvent(input.applicationId(), input.taskId(), input.actor(), input.action(),
                input.details(), input.metadata());
    }

    // ------------------------------------------------------------------ utils

    private static <T> Set<String> ids(List<T> items, Function<T, String> f) {
        Set<String> s = new LinkedHashSet<>();
        items.forEach(i -> s.add(f.apply(i)));
        return s;
    }

    private static <V> Map<Application, V> remap(List<Application> apps, Function<Application, V> f) {
        Map<Application, V> m = new LinkedHashMap<>();
        apps.forEach(a -> m.put(a, f.apply(a)));
        return m;
    }
}
