-- Enable UUID generation if using gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ============================================================================
-- 1. ENUM TYPES
-- ============================================================================

CREATE TYPE application_funding_status AS ENUM (
  'READY_TO_FUND',
  'PENDING_STIPULATIONS',
  'PENDING_AGENT_VERIFICATION',
  'EXCEPTION_HOLD',
  'FUNDED',
  'EXPIRED',
  'DECLINED',
  'WITHDRAWN'
);

CREATE TYPE funding_task_status AS ENUM (
  'VERIFIED',
  'PENDING_REVIEW',
  'DISCREPANCY_FLAGGED',
  'WAIVED',
  'EXPIRED'
);

CREATE TYPE funding_task_category AS ENUM (
  'VIN_VERIFICATION',
  'GAP_FORM_VERIFICATION',
  'INCOME_VERIFICATION',
  'TITLE_AND_LIEN',
  'TILA_RECONCILIATION',
  'INSURANCE_BINDER',
  'OTHER'
);

CREATE TYPE vehicle_condition AS ENUM (
  'NEW',
  'USED',
  'CPO'
);

CREATE TYPE actor_role AS ENUM (
  'FUNDER',
  'SYSTEM',
  'DEALER_FI',
  'UNDERWRITER'
);

-- Optional: bucket enum to mirror RISC / Truth-in-Lending itemization groups 
CREATE TYPE risc_item_bucket AS ENUM ( 'FRONT_END', 'BACK_END', 'PAID_TO_OTHERS' );

-- ============================================================================
-- 2. CORE ENTITIES: DEALERS & APPLICATIONS
-- ============================================================================

-- Every person or system that can act on a deal. Referenced by funding_tasks.verified_by
-- and activity_events.actor so names stay consistent across both.
CREATE TABLE actors (
  actor_name  TEXT PRIMARY KEY,
  role        actor_role NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- One owning funder and one automated engine per task category.
CREATE TABLE task_category_owners (
  category     funding_task_category PRIMARY KEY,
  funder_name  TEXT NOT NULL REFERENCES actors(actor_name) ON UPDATE CASCADE,
  engine_name  TEXT REFERENCES actors(actor_name) ON UPDATE CASCADE,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE dealers (
  dealer_id           TEXT PRIMARY KEY,
  legal_name          TEXT NOT NULL,
  dba_name            TEXT,
  elt_lienholder_code TEXT,
  state_code          CHAR(2),
  created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE applications (
  application_id        TEXT PRIMARY KEY,
  deal_number           TEXT NOT NULL UNIQUE,
  dealer_id             TEXT NOT NULL REFERENCES dealers(dealer_id),
  dealer_name_snapshot  TEXT NOT NULL,
  state_code            CHAR(2) NOT NULL DEFAULT 'TX',
  submitted_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  submitted_display     TEXT,
  sla_minutes_remaining INTEGER NOT NULL DEFAULT 60,
  sla_cutoff_at         TIMESTAMPTZ,
  expires_at            TIMESTAMPTZ,
  status                application_funding_status NOT NULL DEFAULT 'PENDING_STIPULATIONS',
  funded_at             TIMESTAMPTZ,
  created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================================
-- 3. APPLICANT, VEHICLE, AND LOAN TERMS
-- ============================================================================

CREATE TABLE applicants (
  application_id          TEXT PRIMARY KEY REFERENCES applications(application_id) ON DELETE CASCADE,
  full_name               TEXT NOT NULL,
  co_applicant_name       TEXT,
  ssn_last4               CHAR(4) NOT NULL CHECK (ssn_last4 ~ '^[0-9]{4}$'),
  date_of_birth           DATE NOT NULL,
  phone                   TEXT,
  email                   TEXT,
  address_line1           TEXT NOT NULL,
  city                    TEXT,
  state_code              CHAR(2),
  postal_code             TEXT,
  fico_score              INTEGER CHECK (fico_score BETWEEN 300 AND 850),
  stated_monthly_income   NUMERIC(12, 2) NOT NULL CHECK (stated_monthly_income >= 0),
  verified_monthly_income NUMERIC(12, 2) CHECK (verified_monthly_income >= 0),
  created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE vehicles (
  application_id TEXT PRIMARY KEY REFERENCES applications(application_id) ON DELETE CASCADE,
  model_year     INTEGER NOT NULL CHECK (model_year BETWEEN 1980 AND 2100),
  make           TEXT NOT NULL,
  model          TEXT NOT NULL,
  trim           TEXT,
  vin            CHAR(17) NOT NULL CHECK (char_length(vin) = 17),
  mileage        INTEGER NOT NULL DEFAULT 0 CHECK (mileage >= 0),
  condition      vehicle_condition NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE loan_terms (
  application_id   TEXT PRIMARY KEY REFERENCES applications(application_id) ON DELETE CASCADE,
  amount_financed  NUMERIC(12, 2) NOT NULL CHECK (amount_financed >= 0),
  cash_price       NUMERIC(12, 2) NOT NULL CHECK (cash_price >= 0),
  down_payment     NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (down_payment >= 0),
  trade_allowance  NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (trade_allowance >= 0),
  gap_product_price NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (gap_product_price >= 0),
  gap_form_number  TEXT,
  apr              NUMERIC(6, 3) NOT NULL CHECK (apr >= 0),
  term_months      INTEGER NOT NULL CHECK (term_months > 0),
  monthly_payment  NUMERIC(12, 2) NOT NULL CHECK (monthly_payment >= 0),
  ltv_percent      NUMERIC(6, 2),
  dti_percent      NUMERIC(6, 2),
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================================
-- 4. STIPULATIONS / FUNDING TASKS & AUDIT ACTIVITY LOG
-- ============================================================================

CREATE TABLE funding_tasks (
  task_id              TEXT PRIMARY KEY,
  application_id       TEXT NOT NULL REFERENCES applications(application_id) ON DELETE CASCADE,
  category             funding_task_category NOT NULL,
  title                TEXT NOT NULL,
  description          TEXT NOT NULL,
  status               funding_task_status NOT NULL DEFAULT 'PENDING_REVIEW',
  verified_by          TEXT REFERENCES actors(actor_name) ON UPDATE CASCADE,
  updated_display      TEXT,
  source_comparison    JSONB, -- e.g. {"labelA":"Retail Contract","valueA":"...","labelB":"Binder","valueB":"..."}
  ocr_evidence         JSONB, -- optional OCR confidence & bounding/document metadata
  stipulation          JSONB, -- stip request details (reason, target, queue, requested/due dates)
  created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE activity_events (
  event_id           TEXT PRIMARY KEY,
  application_id     TEXT NOT NULL REFERENCES applications(application_id) ON DELETE CASCADE,
  occurred_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  timestamp_display  TEXT,
  task_id            TEXT REFERENCES funding_tasks(task_id) ON DELETE SET NULL,
  actor              TEXT NOT NULL REFERENCES actors(actor_name) ON UPDATE CASCADE,
  role               actor_role NOT NULL,
  action             TEXT NOT NULL,
  details            TEXT,
  metadata           JSONB
);

CREATE TABLE risc_line_items (
  line_item_id   TEXT PRIMARY KEY,
  application_id TEXT NOT NULL REFERENCES applications(application_id) ON DELETE CASCADE,
  bucket         risc_item_bucket NOT NULL,
  item_type      TEXT NOT NULL,   -- e.g. 'VEHICLE_PRICE', 'DOC_FEE', 'GAP', 'VSC', 'REGISTRATION', 'LIEN_FEE'
  description    TEXT NOT NULL,   -- e.g. 'GAP Waiver Addendum', 'Vehicle Service Contract'
  form_number    TEXT,            -- e.g. 'GAP-TX-9940-REV25', 'VSC-8820'
  payee_name     TEXT,            -- e.g. 'TX Dept of Motor Vehicles', 'Apex Metro Honda'
  amount         NUMERIC(12, 2) NOT NULL CHECK (amount >= 0),
  line_order     INTEGER NOT NULL DEFAULT 1 CHECK (line_order > 0),
  is_verified    BOOLEAN NOT NULL DEFAULT FALSE,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
-- ============================================================================
-- 5. INDEXES FOR FUNDER QUEUE FILTERING, SEARCH & JOINS
-- ============================================================================

CREATE INDEX idx_applications_status_submitted
  ON applications (status, submitted_at DESC);

CREATE INDEX idx_applications_dealer_status
  ON applications (dealer_id, status);

CREATE INDEX idx_applications_sla_remaining
  ON applications (sla_minutes_remaining ASC)
  WHERE status IN ('PENDING_STIPULATIONS', 'PENDING_AGENT_VERIFICATION', 'EXCEPTION_HOLD', 'READY_TO_FUND');

CREATE INDEX idx_vehicles_vin
  ON vehicles (vin);

CREATE INDEX idx_applicants_full_name_trgm
  ON applicants USING gin (to_tsvector('english', full_name));

CREATE INDEX idx_funding_tasks_application_status
  ON funding_tasks (application_id, status);

CREATE INDEX idx_activity_events_application_time
  ON activity_events (application_id, occurred_at DESC);

CREATE INDEX idx_activity_events_task
  ON activity_events (task_id) WHERE task_id IS NOT NULL;

CREATE INDEX idx_risc_line_items_app_bucket ON risc_line_items (application_id, bucket, line_order); 

-- ============================================================================
-- 6. AUTOMATIC updated_at TRIGGER
-- ============================================================================

CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = NOW();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_actors_updated_at
  BEFORE UPDATE ON actors
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_task_category_owners_updated_at
  BEFORE UPDATE ON task_category_owners
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_dealers_updated_at
  BEFORE UPDATE ON dealers
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_applications_updated_at
  BEFORE UPDATE ON applications
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_applicants_updated_at
  BEFORE UPDATE ON applicants
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_vehicles_updated_at
  BEFORE UPDATE ON vehicles
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_loan_terms_updated_at
  BEFORE UPDATE ON loan_terms
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_funding_tasks_updated_at
  BEFORE UPDATE ON funding_tasks
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
  
CREATE TRIGGER trg_risc_line_items_updated_at BEFORE UPDATE ON risc_line_items FOR EACH ROW EXECUTE FUNCTION set_updated_at();