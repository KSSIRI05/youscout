-- Chaque événement n'est compté qu'une fois, même relu après une panne.
CREATE TABLE analytics_inbox (
    event_id UUID PRIMARY KEY,
    topic VARCHAR(249) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE analytics_rejected_messages (
    topic VARCHAR(249) NOT NULL,
    partition_id INTEGER NOT NULL,
    record_offset BIGINT NOT NULL,
    reason VARCHAR(40) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (topic, partition_id, record_offset)
);

-- Compteurs agrégés par jour UTC de l'événement : aucun identifiant de compte n'est conservé.
CREATE TABLE daily_activity (
    day DATE NOT NULL,
    metric VARCHAR(20) NOT NULL CHECK (metric IN ('NEW_ACCOUNTS', 'VIDEOS_PUBLISHED', 'LIKES', 'COMMENTS', 'MESSAGES')),
    count BIGINT NOT NULL CHECK (count >= 0),
    PRIMARY KEY (day, metric)
);
