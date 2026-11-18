# Rating Service

Notes des compétences d'une vidéo publiée (U15). Les requêtes nécessitent un JWT Keycloak valide avec le rôle `user` ou `admin` ; l'évaluateur vient exclusivement du claim `sub`.

- `GET /v1/ratings/videos/{videoId}` : pour chaque compétence notée, nombre de notes, moyenne (2 décimales) et note de l'appelant (`myScore`, `null` s'il n'a pas noté).
- `PUT /v1/ratings/videos/{videoId}/skills/{skillId}` avec `{"score": 1..5}` : crée ou modifie la note de l'appelant ; renvoie le résumé de la vidéo.
- `DELETE /v1/ratings/videos/{videoId}/skills/{skillId}` : retire la note de l'appelant ; renvoie le résumé.

Le contrat complet est [rating.yaml](../../contracts/openapi/rating.yaml).

## Règles appliquées

- **Échelle** : entier de 1 à 5 (400 sinon). L'échelle n'était pas fixée dans [le cadrage](../../docs/PROJECT.md) ; 1 à 5 est le choix retenu ici.
- **Unicité** : une note par `(rater_subject, video_id, skill_id)` (clé primaire). Modifier sa note est un seul `INSERT … ON CONFLICT DO UPDATE`, sûr même en cas d'appels simultanés.
- **Vidéo publiée et compétence associée** : Video confirme la publication via `GET /v1/videos/{id}/publication`, qui renvoie aussi `skillIds`. Une vidéo non publiée, supprimée ou retirée donne 404, une compétence absente de la vidéo aussi.
- **Propriétaire** : on ne note pas sa propre vidéo (403).
- **Blocage** : User fournit `GET /v1/users/me/feed-relations` ; un blocage dans un sens ou dans l'autre avec le propriétaire interdit la note (403). Retirer sa note reste toujours possible.

Ces appels transmettent le jeton de l'utilisateur, avec un délai de 3 secondes. Si Video ou User est indisponible, l'écriture est refusée (503). Dans Compose, Video n'existe qu'avec le profil `video` : sans lui, les lectures fonctionnent mais les notes répondent 503.

Le service possède sa base PostgreSQL `youscout_rating` et ne lit pas les bases User ou Video. L'événement `RatingChanged` (`rating.events.v1`) n'est pas encore émis : aucun consommateur (classement du fil, analytics) n'existe pour l'instant.

## Tests

`RatingIntegrationTest` utilise PostgreSQL (Testcontainers) et des doublures HTTP de User et Video : unicité et modification, bornes de la note, compétence absente, propriétaire, blocages, indisponibilité de Video et écritures simultanées. `scripts/social_smoke.py` vérifie le câblage réel Gateway → Rating → Video, et `scripts/demo-e2e.py` le contrôle des compétences sur une vidéo réellement publiée.
