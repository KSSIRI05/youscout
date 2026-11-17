ALTER TABLE feed_entries
    ADD COLUMN skills JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN hashtags JSONB NOT NULL DEFAULT '[]'::jsonb;
