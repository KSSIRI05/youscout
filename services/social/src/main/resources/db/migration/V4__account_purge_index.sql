-- U22 : l'effacement d'un compte fermé retrouve ses commentaires sans parcourir la table.
CREATE INDEX video_comments_author_idx ON video_comments (author_subject);
