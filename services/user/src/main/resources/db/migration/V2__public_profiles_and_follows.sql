CREATE TABLE user_follows (
    follower_subject VARCHAR(255) NOT NULL,
    followed_subject VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (follower_subject, followed_subject),
    CHECK (follower_subject <> followed_subject)
);

CREATE INDEX user_follows_followed_idx ON user_follows (followed_subject, follower_subject);
