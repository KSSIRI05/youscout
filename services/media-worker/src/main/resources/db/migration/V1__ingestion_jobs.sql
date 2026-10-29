-- Base propre au worker : aucune lecture ou écriture dans la base Video.
CREATE TABLE processing_jobs (
    event_id UUID PRIMARY KEY,
    video_id UUID NOT NULL UNIQUE,
    fingerprint CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'QUEUED' CHECK (status = 'QUEUED'),
    s3_bucket VARCHAR(63) NOT NULL,
    s3_key TEXT NOT NULL,
    source_etag VARCHAR(1024) NOT NULL,
    content_type VARCHAR(40) NOT NULL CHECK (content_type IN ('video/mp4', 'video/webm', 'video/quicktime')),
    size_bytes BIGINT NOT NULL CHECK (size_bytes BETWEEN 1 AND 5368709120),
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX processing_jobs_queue_idx ON processing_jobs (created_at, event_id) WHERE status = 'QUEUED';

-- Quarantaine durable des messages invalides ; aucun payload, jeton ou URL enregistré.
CREATE TABLE rejected_messages (
    topic VARCHAR(249) NOT NULL,
    partition_id INTEGER NOT NULL,
    record_offset BIGINT NOT NULL,
    reason VARCHAR(40) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (topic, partition_id, record_offset)
);
