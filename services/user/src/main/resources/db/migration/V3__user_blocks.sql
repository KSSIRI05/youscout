CREATE TABLE user_blocks (
    blocker_subject VARCHAR(255) NOT NULL,
    blocked_subject VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (blocker_subject, blocked_subject),
    CHECK (blocker_subject <> blocked_subject)
);

CREATE INDEX user_blocks_blocked_idx ON user_blocks (blocked_subject, blocker_subject);
