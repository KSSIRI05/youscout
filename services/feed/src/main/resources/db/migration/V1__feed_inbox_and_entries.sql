-- Projection indépendante du service source ; les événements invalides sont acquittés après quarantaine durable.
CREATE TABLE feed_event_inbox (
    event_id UUID PRIMARY KEY,
    video_id UUID NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE feed_entries (
    video_id UUID PRIMARY KEY,
    source_event_id UUID NOT NULL UNIQUE REFERENCES feed_event_inbox(event_id),
    owner_subject TEXT NOT NULL CHECK (length(btrim(owner_subject)) > 0),
    title VARCHAR(150) NOT NULL CHECK (length(btrim(title)) > 0),
    published_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX feed_entries_published_idx ON feed_entries (published_at DESC, video_id DESC);

CREATE TABLE feed_rejected_messages (
    topic VARCHAR(249) NOT NULL,
    partition_id INTEGER NOT NULL,
    record_offset BIGINT NOT NULL,
    reason VARCHAR(40) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (topic, partition_id, record_offset)
);
