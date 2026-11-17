CREATE TABLE user_event_sequence (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    next_value BIGINT NOT NULL CHECK (next_value > 0)
);
INSERT INTO user_event_sequence(singleton, next_value) VALUES (TRUE, 1);

CREATE TABLE user_event_outbox (
    event_id UUID PRIMARY KEY,
    event_sequence BIGINT NOT NULL UNIQUE CHECK (event_sequence > 0),
    message_key VARCHAR(100) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    published_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0)
);

CREATE INDEX user_event_outbox_pending_idx
    ON user_event_outbox (created_at, event_id)
    WHERE published_at IS NULL;

-- Backfill the current relationship state before publishing the completion marker.
-- A single Kafka key preserves this snapshot's order in the MVP's one-partition topic.
WITH existing_relations AS (
    SELECT 1 AS priority, 'FOLLOW'::VARCHAR AS action,
           follower_subject AS actor, followed_subject AS target
      FROM user_follows
    UNION ALL
    SELECT 2 AS priority, 'BLOCK'::VARCHAR AS action,
           blocker_subject AS actor, blocked_subject AS target
      FROM user_blocks
), ordered_relations AS (
    SELECT row_number() OVER (ORDER BY priority, actor, target) AS sequence,
           action, actor, target
      FROM existing_relations
), snapshot_events AS (
    SELECT sequence, action, actor, target,
           gen_random_uuid() AS event_id,
           clock_timestamp() + sequence * interval '1 microsecond' AS occurred_at
      FROM ordered_relations
    UNION ALL
    SELECT COALESCE((SELECT max(sequence) FROM ordered_relations), 0) + 1,
           'SNAPSHOT_COMPLETE', '', '', gen_random_uuid(),
           clock_timestamp() + (COALESCE((SELECT max(sequence) FROM ordered_relations), 0) + 1)
               * interval '1 microsecond'
)
INSERT INTO user_event_outbox (event_id, event_sequence, message_key, event_type, payload, created_at)
SELECT event_id,
       sequence,
       'youscout-user-relationships-v1',
       CASE action
           WHEN 'FOLLOW' THEN 'UserRelationChanged'
           WHEN 'BLOCK' THEN 'UserRelationChanged'
           ELSE 'UserRelationshipsSnapshotComplete'
       END,
       jsonb_build_object(
           'eventId', event_id,
           'eventType', CASE WHEN action = 'SNAPSHOT_COMPLETE'
                             THEN 'UserRelationshipsSnapshotComplete'
                             ELSE 'UserRelationChanged' END,
           'eventVersion', 1,
           'occurredAt', occurred_at,
           'producer', 'user-service',
           'data', CASE WHEN action = 'SNAPSHOT_COMPLETE' THEN '{}'::jsonb
                        ELSE jsonb_build_object('action', action,
                                                'actorSubject', actor,
                                                'targetSubject', target) END
       ),
       occurred_at
  FROM snapshot_events
 ORDER BY sequence;

UPDATE user_event_sequence
   SET next_value = (SELECT COALESCE(MAX(event_sequence), 0) + 1 FROM user_event_outbox)
 WHERE singleton = TRUE;
