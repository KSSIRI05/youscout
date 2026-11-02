CREATE TABLE video_likes (video_id UUID NOT NULL, user_subject VARCHAR(255) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), PRIMARY KEY (video_id, user_subject));
CREATE INDEX video_likes_user_idx ON video_likes (user_subject, created_at DESC);
CREATE TABLE video_comments (id UUID PRIMARY KEY, video_id UUID NOT NULL, author_subject VARCHAR(255) NOT NULL, body VARCHAR(1000) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), deleted_at TIMESTAMPTZ);
CREATE INDEX video_comments_video_idx ON video_comments (video_id, created_at);
CREATE TABLE video_reports (id UUID PRIMARY KEY, video_id UUID NOT NULL, reporter_subject VARCHAR(255) NOT NULL, reason VARCHAR(500) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'REVIEWED', 'DISMISSED')), created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), UNIQUE (video_id, reporter_subject));
CREATE TABLE direct_messages (id UUID PRIMARY KEY, sender_subject VARCHAR(255) NOT NULL, recipient_subject VARCHAR(255) NOT NULL, body VARCHAR(2000) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), CHECK (sender_subject <> recipient_subject));
CREATE INDEX direct_messages_conversation_idx ON direct_messages (sender_subject, recipient_subject, created_at);
CREATE INDEX direct_messages_recipient_idx ON direct_messages (recipient_subject, created_at DESC);
