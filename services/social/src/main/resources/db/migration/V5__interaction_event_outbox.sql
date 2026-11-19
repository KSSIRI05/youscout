-- Événements d'interaction publiés après commit (VideoLiked, CommentAdded) ; Notification les consomme.
-- Aucun texte de commentaire n'y figure : le consommateur relit Social s'il a besoin du contenu.
CREATE TABLE social_event_outbox (
    event_id UUID PRIMARY KEY,
    event_sequence BIGSERIAL UNIQUE,
    message_key VARCHAR(100) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    published_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0)
);
CREATE INDEX social_event_outbox_pending_idx ON social_event_outbox (event_sequence) WHERE published_at IS NULL;
