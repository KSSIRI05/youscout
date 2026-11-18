-- U15 : une note de 1 à 5 par évaluateur, vidéo et compétence ; la modifier remplace la précédente.
CREATE TABLE skill_ratings (
    rater_subject VARCHAR(255) NOT NULL,
    video_id UUID NOT NULL,
    skill_id UUID NOT NULL,
    score SMALLINT NOT NULL CHECK (score BETWEEN 1 AND 5),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (rater_subject, video_id, skill_id)
);
-- Les moyennes se calculent par vidéo.
CREATE INDEX skill_ratings_video_idx ON skill_ratings (video_id, skill_id);
