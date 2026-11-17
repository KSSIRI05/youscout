-- U12 : une réponse vise un commentaire racine de la même vidéo.
-- U13 : le propriétaire de la vidéo, copié depuis Video à la création, peut retirer les commentaires.
ALTER TABLE video_comments ADD COLUMN parent_id UUID REFERENCES video_comments(id);
ALTER TABLE video_comments ADD COLUMN video_owner_subject VARCHAR(255);
ALTER TABLE video_comments ADD CONSTRAINT video_comments_not_own_parent CHECK (parent_id IS NULL OR parent_id <> id);
CREATE INDEX video_comments_parent_idx ON video_comments (parent_id) WHERE parent_id IS NOT NULL;

-- Les pages les plus récentes sont lues en ordre décroissant ; l'identifiant départage les horodatages égaux.
DROP INDEX video_comments_video_idx;
CREATE INDEX video_comments_video_idx ON video_comments (video_id, created_at DESC, id DESC);
DROP INDEX direct_messages_conversation_idx;
CREATE INDEX direct_messages_conversation_idx ON direct_messages (sender_subject, recipient_subject, created_at DESC, id DESC);
