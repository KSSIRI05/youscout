-- Déduplication durable des événements Kafka : un eventId n'est appliqué qu'une fois, même après rejeu.
CREATE TABLE notification_inbox (
    event_id UUID PRIMARY KEY,
    topic VARCHAR(249) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- Un message invalide est mis de côté puis acquitté, sans bloquer la partition.
CREATE TABLE notification_rejected_messages (
    topic VARCHAR(249) NOT NULL,
    partition_id INTEGER NOT NULL,
    record_offset BIGINT NOT NULL,
    reason VARCHAR(40) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (topic, partition_id, record_offset)
);

CREATE TABLE notifications (
    id UUID PRIMARY KEY,
    recipient_subject VARCHAR(255) NOT NULL,
    type VARCHAR(20) NOT NULL CHECK (type IN ('FOLLOWED', 'VIDEO_PUBLISHED', 'VIDEO_LIKED', 'COMMENTED', 'REPLIED', 'MESSAGE')),
    actor_subject VARCHAR(255) NOT NULL,
    video_id UUID,
    comment_id UUID,
    conversation_id UUID,
    -- Messages d'une même conversation regroupés tant que la notification n'est pas lue.
    occurrences INTEGER NOT NULL DEFAULT 1 CHECK (occurrences > 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    read_at TIMESTAMPTZ,
    push_status VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (push_status IN ('PENDING', 'SENT', 'NO_DEVICE', 'DISABLED', 'FAILED')),
    CHECK (recipient_subject <> actor_subject)
);
CREATE INDEX notifications_recipient_idx ON notifications (recipient_subject, updated_at DESC, id DESC);
CREATE INDEX notifications_actor_idx ON notifications (actor_subject);
CREATE INDEX notifications_video_idx ON notifications (video_id) WHERE video_id IS NOT NULL;
CREATE INDEX notifications_push_idx ON notifications (updated_at) WHERE push_status = 'PENDING';
-- Pas de doublon non lu : un suivi, un like ou une conversation répétés ne créent qu'une notification.
CREATE UNIQUE INDEX notifications_unread_follow_idx ON notifications (recipient_subject, actor_subject)
    WHERE type = 'FOLLOWED' AND read_at IS NULL;
CREATE UNIQUE INDEX notifications_unread_like_idx ON notifications (recipient_subject, actor_subject, video_id)
    WHERE type = 'VIDEO_LIKED' AND read_at IS NULL;
CREATE UNIQUE INDEX notifications_unread_message_idx ON notifications (recipient_subject, conversation_id)
    WHERE type = 'MESSAGE' AND read_at IS NULL;

-- Projection des relations publiées par User, propre à Notification.
CREATE TABLE relation_follows (
    follower_subject VARCHAR(255) NOT NULL,
    followed_subject VARCHAR(255) NOT NULL,
    PRIMARY KEY (follower_subject, followed_subject)
);
CREATE INDEX relation_follows_followed_idx ON relation_follows (followed_subject);
CREATE TABLE relation_blocks (
    blocker_subject VARCHAR(255) NOT NULL,
    blocked_subject VARCHAR(255) NOT NULL,
    PRIMARY KEY (blocker_subject, blocked_subject)
);
CREATE INDEX relation_blocks_blocked_idx ON relation_blocks (blocked_subject);
-- Comptes désactivés, en fermeture ou fermés : ni destinataires ni auteurs. Le sujet seul est conservé.
CREATE TABLE inactive_accounts (subject VARCHAR(255) PRIMARY KEY);

CREATE TABLE devices (
    id UUID PRIMARY KEY,
    subject VARCHAR(255) NOT NULL,
    platform VARCHAR(10) NOT NULL CHECK (platform IN ('ANDROID', 'IOS', 'WEB')),
    -- Empreinte pour l'unicité ; le jeton lui-même n'est stocké que chiffré.
    token_hash CHAR(64) NOT NULL UNIQUE,
    token_ciphertext TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX devices_subject_idx ON devices (subject);
