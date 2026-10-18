-- Les URL signées sont des secrets temporaires : seule la clé S3 est persistée.
CREATE TABLE videos (
    id UUID PRIMARY KEY,
    owner_subject TEXT NOT NULL CHECK (length(btrim(owner_subject)) > 0),
    title VARCHAR(150) NOT NULL CHECK (length(btrim(title)) > 0),
    s3_bucket VARCHAR(63) NOT NULL,
    s3_key TEXT NOT NULL,
    content_type VARCHAR(40) NOT NULL CHECK (content_type IN ('video/mp4', 'video/webm', 'video/quicktime')),
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING_UPLOAD' CHECK (status IN ('PENDING_UPLOAD')),
    idempotency_key VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (owner_subject, idempotency_key),
    UNIQUE (s3_bucket, s3_key)
);
CREATE INDEX videos_owner_created_idx ON videos (owner_subject, created_at DESC, id DESC);
