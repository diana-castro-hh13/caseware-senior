-- Tracking tables. One PostgreSQL database per data region (for example US, EU, Canada).
-- They hold only IDs and version numbers, never any audit work or client data.

-- ---------------------------------------------------------------------------------------------
-- Who can connect
--   api_role      : used by the Pending Updates API. It can only see ONE firm at a time.
--   checker_role  : used by the background checker (Part 2). It works across all firms, but it
--                   cannot read or write decisions.
-- Neither role owns the tables and neither can bypass row-level security.
-- ---------------------------------------------------------------------------------------------
CREATE ROLE api_role     NOLOGIN NOBYPASSRLS;
CREATE ROLE checker_role NOLOGIN NOBYPASSRLS;

-- A copy of the list of template versions. Template content is the same for every firm,
-- so this table holds no firm data and every role may read it.
CREATE TABLE template_version (
    template_id    TEXT        NOT NULL,
    version        INT         NOT NULL,
    branch         TEXT        NOT NULL,          -- market, e.g. 'CA', 'UK'
    parent_version INT,                           -- the version this one was based on
    status         TEXT        NOT NULL CHECK (status IN ('PUBLISHED', 'WITHDRAWN')),
    published_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (template_id, version)
);

-- One row per engagement file: which template version it is on.
CREATE TABLE engagement_binding (
    engagement_id    UUID        PRIMARY KEY,
    firm_id          UUID        NOT NULL,
    status           TEXT        NOT NULL CHECK (status IN ('UNKNOWN', 'VERIFIED', 'SUSPECT', 'FAILED')),
    template_id      TEXT,
    branch           TEXT,
    current_version  INT,
    declined_version INT,                         -- the version the user last said no to (NULL = none)
    observed_at      TIMESTAMPTZ,                 -- when this info was true; older info never replaces newer
    lease_owner      TEXT,                        -- which worker reserved this row, if any
    lease_until      TIMESTAMPTZ,                 -- when that reservation expires
    failure_reason   TEXT,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_binding_firm       ON engagement_binding (firm_id);
CREATE INDEX ix_binding_template   ON engagement_binding (template_id, branch, current_version);
CREATE INDEX ix_binding_unverified ON engagement_binding (engagement_id) WHERE status IN ('UNKNOWN', 'SUSPECT');

-- History of every apply/decline decision. Rows are only ever added, never changed or deleted.
CREATE TABLE decision_audit (
    decision_id  UUID        PRIMARY KEY,
    engagement_id UUID       NOT NULL,
    firm_id      UUID        NOT NULL,
    user_id      TEXT        NOT NULL,
    action       TEXT        NOT NULL CHECK (action IN ('APPLY', 'DECLINE')),
    from_version INT         NOT NULL,            -- the version the user saw as current
    to_version   INT         NOT NULL,            -- the version that was offered
    summary_id   TEXT        NOT NULL,            -- which summary was on the screen
    decided_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_firm ON decision_audit (firm_id, decided_at);

-- ---------------------------------------------------------------------------------------------
-- Firm isolation, enforced by the database itself.
-- The API sets the firm once per request (after checking the user's permissions):
--     SET LOCAL app.firm_id = '<firm uuid>';
-- From then on the database only shows that firm's rows, even if the application code
-- forgets a WHERE clause. If the setting is missing, no rows are visible (it fails closed).
-- ---------------------------------------------------------------------------------------------
ALTER TABLE engagement_binding ENABLE ROW LEVEL SECURITY;
ALTER TABLE engagement_binding FORCE  ROW LEVEL SECURITY;
ALTER TABLE decision_audit     ENABLE ROW LEVEL SECURITY;
ALTER TABLE decision_audit     FORCE  ROW LEVEL SECURITY;

CREATE POLICY firm_only ON engagement_binding TO api_role
    USING      (firm_id = NULLIF(current_setting('app.firm_id', true), '')::uuid)
    WITH CHECK (firm_id = NULLIF(current_setting('app.firm_id', true), '')::uuid);

CREATE POLICY checker_all ON engagement_binding TO checker_role
    USING (true) WITH CHECK (true);

CREATE POLICY firm_only ON decision_audit TO api_role
    USING      (firm_id = NULLIF(current_setting('app.firm_id', true), '')::uuid)
    WITH CHECK (firm_id = NULLIF(current_setting('app.firm_id', true), '')::uuid);

GRANT SELECT                          ON template_version   TO api_role, checker_role;
GRANT SELECT, UPDATE                  ON engagement_binding TO api_role;      -- the API records declines
GRANT SELECT, INSERT, UPDATE          ON engagement_binding TO checker_role;
GRANT SELECT, INSERT                  ON decision_audit     TO api_role;      -- no UPDATE, no DELETE

-- ---------------------------------------------------------------------------------------------
-- What the Java code runs (see EngagementStateStore)
--
-- findUnverified(after, limit): next rows we still need to check
--   SELECT engagement_id, firm_id FROM engagement_binding
--   WHERE status IN ('UNKNOWN', 'SUSPECT')
--     AND (lease_until IS NULL OR lease_until < now())
--     AND (:after IS NULL OR engagement_id > :after)
--   ORDER BY engagement_id LIMIT :limit;
--
-- tryClaim(...): reserve a row. If 1 row changed, we got it.
--   UPDATE engagement_binding SET lease_owner = :owner, lease_until = :leaseUntil
--   WHERE engagement_id = :id AND status <> 'FAILED'
--     AND (lease_until IS NULL OR lease_until < now())
--     AND (observed_at IS NULL OR observed_at < :notBefore);
--
-- recordVerified(...): save the answer, unless we already have newer info
--   UPDATE engagement_binding
--   SET template_id = :templateId, branch = :branch, current_version = :version,
--       status = 'VERIFIED', observed_at = :observedAt, updated_at = now()
--   WHERE engagement_id = :id AND (observed_at IS NULL OR observed_at < :observedAt);
--   UPDATE engagement_binding SET lease_owner = NULL, lease_until = NULL WHERE engagement_id = :id;
--
-- The query behind the screen: "which of this firm's engagements have an update waiting?"
-- It opens no engagement, so it answers in milliseconds.
--   The offer = the newest PUBLISHED version on the same market branch, newer than the current one.
--   It counts as pending only if it is also newer than the version the user last said no to.
--   SELECT b.engagement_id, b.current_version, o.version AS offered_version
--   FROM engagement_binding b
--   JOIN LATERAL (
--       SELECT v.version FROM template_version v
--       WHERE v.template_id = b.template_id AND v.branch = b.branch
--         AND v.status = 'PUBLISHED' AND v.version > b.current_version
--       ORDER BY v.version DESC LIMIT 1) o ON true
--   WHERE b.status = 'VERIFIED'                      -- no WHERE firm_id: the database adds it
--     AND o.version > COALESCE(b.declined_version, 0);
