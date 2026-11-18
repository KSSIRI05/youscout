# Social Service

Service de persistance des interactions communautaires. Les requêtes nécessitent un JWT Keycloak valide; l'identité vient exclusivement du claim `sub`.

- `GET`, `PUT`, `DELETE /v1/social/videos/{videoId}/likes` : consulter l'état, aimer ou retirer son like.
- `GET`, `POST /v1/social/videos/{videoId}/comments` : consulter et créer un commentaire ou une réponse (`parentId`).
- `DELETE /v1/social/comments/{commentId}` : supprimer son commentaire, ou un commentaire publié sur sa propre vidéo (U13).
- `POST /v1/social/videos/{videoId}/reports` : créer un signalement (un par compte et par vidéo).
- `POST /v1/social/comments/{commentId}/reports` : signaler un commentaire visible (U17), un par compte.

### Modération (rôle `admin`, contrôlé par la Gateway et par Social)

- `GET /v1/social/admin/reports?type=VIDEO|COMMENT&status=OPEN|REVIEWED|DISMISSED&offset=` : file des cibles signalées, la plus récente d'abord, avec le nombre de signalements et les 5 derniers motifs ; pour un commentaire, son texte, son auteur et sa vidéo (A03).
- `POST /v1/social/admin/reports/{type}/{targetId}/decision` : `DISMISSED` (sans suite) ou `ACTION_TAKEN` ferme tous les signalements ouverts de la cible (A04).
- `POST /v1/social/admin/comments/{commentId}/remove` : retire le commentaire de toutes les lectures et ferme ses signalements ; un nouvel appel ne change rien.

Chaque décision enregistre l'administrateur (`sub` du jeton), la date, une note facultative et le nombre de signalements fermés dans `moderation_decisions`. Les signalements fermés gardent `decided_by` et `decided_at`.

Le contrat complet est [social.yaml](../../contracts/openapi/social.yaml).

## Règles appliquées

- **Vidéo publiée** : liker, commenter et signaler exigent que Video confirme la publication via `GET /v1/videos/{id}/publication` (404 sinon). Le propriétaire renvoyé est copié dans le commentaire pour la règle U13.
- **Blocage** : User fournit `GET /v1/users/me/feed-relations`. Un blocage, dans un sens ou dans l'autre, interdit like, commentaire et réponse (403) et masque les commentaires de ces comptes pour l'appelant. Retirer un like et signaler restent possibles.
- **Réponses** : un seul niveau ; le parent doit être un commentaire principal, non supprimé, de la même vidéo.
- **Pagination** : les commentaires (50 par défaut, 100 max) renvoient la page la plus récente en ordre chronologique. `before={id}` remonte l'historique.

Ces appels transmettent le jeton de l'utilisateur, avec un délai de 3 secondes. Si Video ou User est indisponible, l'écriture est refusée (503) au lieu d'être acceptée sans contrôle. Dans Compose, Video n'existe qu'avec le profil `video` : sans lui, les lectures fonctionnent mais les écritures vérifiées répondent 503.

Le service possède sa base PostgreSQL `youscout_social` et ne lit pas les bases User ou Video. Les messages directs appartiennent désormais au [service Chat](../chat/README.md) ; l'ancienne table `direct_messages` reste en place mais n'est plus utilisée.

## Tests

`SocialIntegrationTest` utilise PostgreSQL (Testcontainers) et des doublures HTTP de User et Video : blocages, pagination, réponses, suppression par le propriétaire, indisponibilité de Video et refus sans rôle. `scripts/social_smoke.py`, appelé par `smoke-identity.py`, vérifie le câblage réel Gateway → Social → Video/User.
