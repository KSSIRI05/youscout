-- Journal durable indépendant de Kafka ; l'API ne dépend pas de sa disponibilité.
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(80) NOT NULL CHECK (event_type = 'VideoUploaded'),
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    published_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    last_error VARCHAR(80),
    UNIQUE (aggregate_id, event_type),
    CHECK (payload->>'eventId' = id::text),
    CHECK (payload->'data'->>'videoId' = aggregate_id::text)
);
CREATE INDEX outbox_pending_idx ON outbox_events (next_attempt_at, created_at, id)
    WHERE published_at IS NULL;

-- Reprise des vidéos déjà confirmées avant cette migration, une seule fois.
-- L'ETag est opaque et doit être repris tel quel dans le futur GET S3 If-Match.
INSERT INTO outbox_events (id, aggregate_id, event_type, payload)
SELECT event_id, id, 'VideoUploaded', jsonb_build_object(
    'eventId', event_id, 'eventType', 'VideoUploaded', 'eventVersion', 1,
    'occurredAt', completed_at, 'producer', 'video-service',
    'data', jsonb_build_object('videoId', id, 's3Bucket', s3_bucket, 's3Key', s3_key,
        'sourceETag', source_etag, 'contentType', content_type, 'sizeBytes', size_bytes))
FROM (SELECT gen_random_uuid() AS event_id, v.* FROM videos v WHERE status = 'UPLOADED') confirmed;
