# Social Service

Service de persistance des interactions communautaires. Les requêtes nécessitent un JWT Keycloak valide; l'identité vient exclusivement du claim `sub`.

- `GET`, `PUT`, `DELETE /v1/social/videos/{videoId}/likes` : consulter l'état, aimer ou retirer son like.
- `GET`, `POST /v1/social/videos/{videoId}/comments` : consulter et créer un commentaire.
- `DELETE /v1/social/comments/{commentId}` : supprimer son propre commentaire.
- `POST /v1/social/videos/{videoId}/reports` : créer un signalement (un par compte et par vidéo).
- `GET /v1/social/messages?with={subject}`, `POST /v1/social/messages` : consulter et envoyer les messages persistés d'une conversation.

Le service possède sa base PostgreSQL `youscout_social` et ne lit pas les bases User ou Video. La messagerie fonctionne par requêtes HTTP; notifications temps réel et console de modération restent à construire.
