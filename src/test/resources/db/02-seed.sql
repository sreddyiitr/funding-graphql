-- Small, deterministic fixture for E2E tests. Re-run before every test.
TRUNCATE risc_line_items, activity_events, funding_tasks, loan_terms, vehicles, applicants,
         applications, dealers, task_category_owners, actors;

INSERT INTO actors (actor_name, role) VALUES
  ('T. Funder', 'FUNDER'),
  ('Test Engine', 'SYSTEM');

INSERT INTO dealers (dealer_id, legal_name, dba_name, state_code) VALUES
  ('dlr_1', 'Test Motors LLC', 'Test Motors', 'TX'),
  ('dlr_2', 'Other Auto Inc', NULL, 'TX');

INSERT INTO applications (application_id, deal_number, dealer_id, dealer_name_snapshot, submitted_at, status) VALUES
  ('app_1', 'D-001', 'dlr_1', 'Test Motors', '2026-11-25T10:00:00Z', 'READY_TO_FUND'),
  ('app_2', 'D-002', 'dlr_1', 'Test Motors', '2026-11-25T09:00:00Z', 'PENDING_STIPULATIONS'),
  ('app_3', 'D-003', 'dlr_2', 'Other Auto',  '2026-11-25T08:00:00Z', 'READY_TO_FUND');

INSERT INTO applicants (application_id, full_name, ssn_last4, date_of_birth, address_line1, fico_score, stated_monthly_income)
VALUES ('app_1', 'Jane Tester', '1234', '1990-01-15', '1 Main St', 720, 6500.00);

INSERT INTO vehicles (application_id, model_year, make, model, vin, mileage, condition)
VALUES ('app_1', 2025, 'Honda', 'CR-V', '1HGCM82633A004352', 12, 'NEW');

INSERT INTO loan_terms (application_id, amount_financed, cash_price, apr, term_months, monthly_payment)
VALUES ('app_1', 30000.00, 32000.00, 4.490, 60, 559.25);

INSERT INTO funding_tasks (task_id, application_id, category, title, description, status, verified_by, source_comparison) VALUES
  ('task_1a', 'app_1', 'VIN_VERIFICATION', 'VIN check', 'Match VIN across docs', 'VERIFIED', 'Test Engine',
   '{"fieldName": "vin", "isMatch": true}'),
  ('task_1b', 'app_1', 'INCOME_VERIFICATION', 'Income check', 'Verify income', 'PENDING_REVIEW', NULL, NULL),
  ('task_2a', 'app_2', 'TITLE_AND_LIEN', 'Title check', 'Verify lien', 'DISCREPANCY_FLAGGED', NULL, NULL);

INSERT INTO risc_line_items (line_item_id, application_id, bucket, item_type, description, amount, line_order) VALUES
  ('li_1', 'app_1', 'FRONT_END', 'VEHICLE_PRICE', 'Cash price', 32000.00, 1),
  ('li_2', 'app_1', 'BACK_END',  'GAP',           'GAP waiver', 795.00, 1);
