package com.funding.graphql;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;

/** Plain records mirroring the Postgres tables. Field names match the GraphQL schema. */
public final class Models {
    private Models() {}

    public record Application(String applicationId, String dealNumber, String dealerId, String dealerNameSnapshot,
                              String stateCode, OffsetDateTime submittedAt, int slaMinutesRemaining,
                              OffsetDateTime slaCutoffAt, OffsetDateTime expiresAt, String status,
                              OffsetDateTime fundedAt, OffsetDateTime updatedAt) {}

    public record Dealer(String dealerId, String legalName, String dbaName, String eltLienholderCode, String stateCode) {}

    public record Applicant(String applicationId, String fullName, String coApplicantName, String ssnLast4,
                            LocalDate dateOfBirth, String phone, String email, String addressLine1, String city,
                            String stateCode, String postalCode, Integer ficoScore, BigDecimal statedMonthlyIncome,
                            BigDecimal verifiedMonthlyIncome) {}

    public record Vehicle(String applicationId, int modelYear, String make, String model, String trim, String vin,
                          int mileage, String condition) {}

    public record LoanTerms(String applicationId, BigDecimal amountFinanced, BigDecimal cashPrice,
                            BigDecimal downPayment, BigDecimal tradeAllowance, BigDecimal gapProductPrice,
                            String gapFormNumber, BigDecimal apr, int termMonths, BigDecimal monthlyPayment,
                            BigDecimal ltvPercent, BigDecimal dtiPercent) {}

    public record FundingTask(String taskId, String applicationId, String category, String title, String description,
                              String status, String verifiedBy, Object sourceComparison, Object ocrEvidence,
                              Object stipulation, OffsetDateTime updatedAt) {}

    public record RiscLineItem(String lineItemId, String applicationId, String bucket, String itemType,
                               String description, String formNumber, String payeeName, BigDecimal amount,
                               int lineOrder, boolean isVerified) {}

    public record ActivityEvent(String eventId, String applicationId, OffsetDateTime occurredAt, String taskId,
                                String actor, String role, String action, String details, Object metadata) {}

    public record Actor(String actorName, String role) {}

    // ---- mutation inputs ----
    public record UpdateApplicationStatusInput(String applicationId, String status, String actor, String details) {}

    public record UpdateFundingTaskStatusInput(String taskId, String status, String actor, String details) {}

    public record AddActivityEventInput(String applicationId, String taskId, String actor, String action,
                                        String details, Map<String, Object> metadata) {}
}
