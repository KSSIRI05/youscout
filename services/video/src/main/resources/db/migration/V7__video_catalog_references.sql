CREATE TABLE video_skills (
    video_id UUID NOT NULL REFERENCES videos(id) ON DELETE CASCADE,
    skill_id UUID NOT NULL,
    name VARCHAR(100) NOT NULL,
    position SMALLINT NOT NULL CHECK (position BETWEEN 0 AND 9),
    PRIMARY KEY (video_id, skill_id),
    UNIQUE (video_id, position)
);

CREATE TABLE video_hashtags (
    video_id UUID NOT NULL REFERENCES videos(id) ON DELETE CASCADE,
    hashtag_id UUID NOT NULL,
    name VARCHAR(80) NOT NULL,
    position SMALLINT NOT NULL CHECK (position BETWEEN 0 AND 9),
    PRIMARY KEY (video_id, hashtag_id),
    UNIQUE (video_id, position)
);
