-- Conserve les demandes existantes ; la confirmation capture l'identité opaque de l'objet observé.
ALTER TABLE videos DROP CONSTRAINT videos_status_check;
ALTER TABLE videos
    ADD COLUMN completed_at TIMESTAMPTZ,
    ADD COLUMN source_etag TEXT,
    ADD CONSTRAINT videos_status_check CHECK (status IN ('PENDING_UPLOAD', 'UPLOADED')),
    ADD CONSTRAINT videos_completion_check CHECK (
        (status = 'PENDING_UPLOAD' AND completed_at IS NULL AND source_etag IS NULL)
        OR
        (status = 'UPLOADED' AND completed_at IS NOT NULL AND source_etag IS NOT NULL
            AND length(btrim(source_etag)) > 0)
    );
