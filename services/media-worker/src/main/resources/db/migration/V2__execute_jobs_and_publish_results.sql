ALTER TABLE processing_jobs DROP CONSTRAINT processing_jobs_status_check;
ALTER TABLE processing_jobs
    ADD CONSTRAINT processing_jobs_status_check CHECK (status IN ('QUEUED', 'PROCESSING', 'SUCCEEDED', 'FAILED')),
    ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN attempt_id UUID,
    ADD COLUMN lease_until TIMESTAMPTZ,
    ADD COLUMN available_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    ADD COLUMN error_code VARCHAR(40),
    ADD COLUMN output_video_key TEXT,
    ADD COLUMN thumbnail_key TEXT,
    ADD COLUMN duration_millis BIGINT,
    ADD COLUMN width INTEGER,
    ADD COLUMN height INTEGER,
    ADD CONSTRAINT job_lease_check CHECK ((status = 'PROCESSING') = (lease_until IS NOT NULL)),
    ADD CONSTRAINT job_success_check CHECK (status <> 'SUCCEEDED' OR
        (output_video_key IS NOT NULL AND thumbnail_key IS NOT NULL AND duration_millis > 0 AND width > 0 AND height > 0));
CREATE INDEX processing_jobs_recovery_idx ON processing_jobs (lease_until) WHERE status = 'PROCESSING';

CREATE TABLE worker_outbox (
    event_id UUID PRIMARY KEY,
    source_event_id UUID NOT NULL UNIQUE REFERENCES processing_jobs(event_id) ON DELETE CASCADE,
    video_id UUID NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    published_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX worker_outbox_pending_idx ON worker_outbox (next_attempt_at) WHERE published_at IS NULL;
