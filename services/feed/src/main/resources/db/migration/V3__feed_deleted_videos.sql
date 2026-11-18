-- Une suppression est définitive : elle retire l'entrée et empêche un VideoPublished arrivé en retard de la recréer.
CREATE TABLE feed_deleted_videos (
    video_id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    owner_subject TEXT NOT NULL CHECK (length(btrim(owner_subject)) > 0),
    deleted_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
