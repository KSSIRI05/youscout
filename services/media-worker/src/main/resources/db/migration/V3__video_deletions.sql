-- Chaque tentative écrit dans son propre dossier processed/{videoId}/{attemptId}/ ; on les conserve toutes
-- pour supprimer ces fichiers sans droit de listage S3.
CREATE TABLE processing_attempts (
    attempt_id UUID PRIMARY KEY,
    video_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX processing_attempts_video_idx ON processing_attempts (video_id);
INSERT INTO processing_attempts(attempt_id, video_id)
SELECT attempt_id, video_id FROM processing_jobs WHERE attempt_id IS NOT NULL;

-- Suppressions reçues de Video ; le nettoyage S3 est rejoué jusqu'au succès.
CREATE TABLE video_deletions (
    video_id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'DONE')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    available_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ,
    CHECK ((status = 'DONE') = (completed_at IS NOT NULL))
);
CREATE INDEX video_deletions_pending_idx ON video_deletions (available_at) WHERE status = 'PENDING';
