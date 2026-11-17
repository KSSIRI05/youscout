-- La publication déclenche un événement distinct après le commit applicatif.
ALTER TABLE outbox_events DROP CONSTRAINT outbox_events_event_type_check;
ALTER TABLE outbox_events ADD CONSTRAINT outbox_events_event_type_check
    CHECK (event_type IN ('VideoUploaded', 'VideoPublished'));

ALTER TABLE outbox_events ADD CONSTRAINT outbox_event_payload_type_check
    CHECK (payload->>'eventType' = event_type);
