-- Dernier état de modération connu par vidéo ; seule une version plus récente le remplace.
-- Une vidéo retirée reste en base et réapparaît si elle est rétablie, sans rejouer sa publication.
CREATE TABLE feed_moderation (
    video_id UUID PRIMARY KEY,
    owner_subject TEXT NOT NULL CHECK (length(btrim(owner_subject)) > 0),
    version BIGINT NOT NULL CHECK (version > 0),
    removed BOOLEAN NOT NULL,
    event_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX feed_moderation_removed_idx ON feed_moderation (video_id) WHERE removed;
