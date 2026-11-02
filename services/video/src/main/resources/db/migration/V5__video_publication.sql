-- L'upload, le traitement et la publication conservent chacun leur propre état.
ALTER TABLE videos
    ADD COLUMN publication_status VARCHAR(20) NOT NULL DEFAULT 'UNPUBLISHED',
    ADD COLUMN published_at TIMESTAMPTZ,
    ADD CONSTRAINT videos_publication_check CHECK (
        (publication_status = 'UNPUBLISHED' AND published_at IS NULL)
        OR
        (publication_status = 'PUBLISHED' AND published_at IS NOT NULL
            AND status = 'UPLOADED' AND processing_status IS NOT DISTINCT FROM 'READY')
    );
