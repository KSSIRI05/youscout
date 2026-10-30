-- L'état d'upload reste stable pour préserver l'idempotence de /complete.
ALTER TABLE videos ADD COLUMN processing_status VARCHAR(20) CHECK (processing_status IN ('WAITING', 'READY', 'FAILED')),
    ADD COLUMN processing_updated_at TIMESTAMPTZ,
    ADD COLUMN processing_error VARCHAR(40),
    ADD COLUMN processed_video_key TEXT,
    ADD COLUMN thumbnail_key TEXT,
    ADD COLUMN duration_millis BIGINT,
    ADD COLUMN width INTEGER,
    ADD COLUMN height INTEGER;
UPDATE videos SET processing_status = 'WAITING', processing_updated_at = completed_at WHERE status = 'UPLOADED';
CREATE TABLE processing_result_inbox (
    event_id UUID PRIMARY KEY,
    video_id UUID NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE rejected_processing_results (
    topic VARCHAR(249) NOT NULL,
    partition_id INTEGER NOT NULL,
    record_offset BIGINT NOT NULL,
    reason VARCHAR(40) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (topic, partition_id, record_offset)
);
