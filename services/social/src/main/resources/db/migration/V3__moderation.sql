-- U17 : un signalement par compte et par commentaire, avec motif.
CREATE TABLE comment_reports (
    id UUID PRIMARY KEY,
    comment_id UUID NOT NULL REFERENCES video_comments(id),
    reporter_subject VARCHAR(255) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'REVIEWED', 'DISMISSED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    decided_at TIMESTAMPTZ,
    decided_by VARCHAR(255),
    UNIQUE (comment_id, reporter_subject),
    CHECK ((status = 'OPEN') = (decided_at IS NULL AND decided_by IS NULL))
);
CREATE INDEX comment_reports_status_idx ON comment_reports (status, comment_id);

-- A03/A04 : une décision ferme les signalements ouverts d'une cible, avec l'administrateur et la date.
ALTER TABLE video_reports ADD COLUMN decided_at TIMESTAMPTZ, ADD COLUMN decided_by VARCHAR(255);
ALTER TABLE video_reports ADD CONSTRAINT video_reports_decision_check
    CHECK ((status = 'OPEN') = (decided_at IS NULL AND decided_by IS NULL));
CREATE INDEX video_reports_status_idx ON video_reports (status, video_id);

-- Un commentaire retiré par la modération reste masqué comme un commentaire supprimé, avec son auteur de décision.
ALTER TABLE video_comments ADD COLUMN removed_by VARCHAR(255);

-- Journal immuable des décisions de modération.
CREATE TABLE moderation_decisions (
    id UUID PRIMARY KEY,
    admin_subject VARCHAR(255) NOT NULL,
    target_type VARCHAR(20) NOT NULL CHECK (target_type IN ('VIDEO', 'COMMENT')),
    target_id UUID NOT NULL,
    decision VARCHAR(30) NOT NULL CHECK (decision IN ('DISMISSED', 'ACTION_TAKEN', 'COMMENT_REMOVED')),
    note VARCHAR(500),
    reports_closed INTEGER NOT NULL CHECK (reports_closed >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX moderation_decisions_target_idx ON moderation_decisions (target_type, target_id, created_at DESC);
