-- A04 : un hashtag désactivé reste en base (vidéos déjà publiées, abonnements) mais n'est plus proposé.
ALTER TABLE hashtags
    ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE TABLE hashtag_audit (
    id UUID PRIMARY KEY,
    hashtag_id UUID NOT NULL REFERENCES hashtags(id),
    actor_subject VARCHAR(255) NOT NULL,
    action VARCHAR(12) NOT NULL CHECK (action IN ('DEACTIVATE', 'REACTIVATE')),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX hashtag_audit_by_hashtag ON hashtag_audit(hashtag_id, occurred_at);
