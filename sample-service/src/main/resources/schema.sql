-- Jobs for the demo execution engine: SCHEDULED -> EXECUTING -> COMPLETED.
CREATE TABLE IF NOT EXISTS engine_job (
    id               BIGSERIAL PRIMARY KEY,
    name             VARCHAR(200) NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    duration_seconds INT          NOT NULL,
    attempts         INT          NOT NULL DEFAULT 0,
    run_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    executed_by      VARCHAR(100),
    executed_by_color VARCHAR(10),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at       TIMESTAMPTZ,
    finished_at      TIMESTAMPTZ
);

-- The one change a real job table needs: which color started the job. Also adds it to tables created before.
ALTER TABLE engine_job ADD COLUMN IF NOT EXISTS executed_by_color VARCHAR(10);

CREATE INDEX IF NOT EXISTS engine_job_status_run_at ON engine_job (status, run_at);

-- One row per piece of background work, written by both colors. Used by scripts/status.sh.
CREATE TABLE IF NOT EXISTS audit_log (
    id       BIGSERIAL PRIMARY KEY,
    at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    color    VARCHAR(10)  NOT NULL,
    instance VARCHAR(100) NOT NULL,
    task     VARCHAR(50)  NOT NULL,
    job_id   BIGINT,
    detail   VARCHAR(500)
);
