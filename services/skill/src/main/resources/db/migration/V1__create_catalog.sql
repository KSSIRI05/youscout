CREATE TABLE skills (
    id UUID PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    name_key VARCHAR(160) NOT NULL UNIQUE,
    description VARCHAR(500) NOT NULL DEFAULT '',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE hashtags (
    id UUID PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE hashtag_follows (
    subject VARCHAR(255) NOT NULL,
    hashtag_id UUID NOT NULL REFERENCES hashtags(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (subject, hashtag_id)
);
CREATE INDEX hashtag_follows_by_tag ON hashtag_follows(hashtag_id);
-- Initial football catalogue; administrators may rename or deactivate entries.
INSERT INTO skills (id, name, name_key) VALUES
 ('00000000-0000-4000-8000-000000000001', 'Dribble', 'dribble'),
 ('00000000-0000-4000-8000-000000000002', 'Frappe', 'frappe'),
 ('00000000-0000-4000-8000-000000000003', 'Passe', 'passe'),
 ('00000000-0000-4000-8000-000000000004', 'Technique', 'technique'),
 ('00000000-0000-4000-8000-000000000005', 'Vision du jeu', 'vision du jeu');
