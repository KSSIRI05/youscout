# Publication d'une vidéo prête

`POST /v1/videos/{id}/publish` enregistre la publication de la vidéo du joueur connecté. Le propriétaire provient exclusivement du `sub` Keycloak. La Gateway et Video exigent le rôle `user` ou `admin` ; un administrateur ne peut pas publier la vidéo d'un autre compte.

L'appel accepte un corps absent ou `{}`. Aucun titre, propriétaire, statut ou horodatage n'est fourni par le client. Une vidéo inconnue ou appartenant à un autre compte renvoie `404`. Une vidéo non reçue, en attente de traitement ou dont le traitement a échoué renvoie `409`.

## Trois états indépendants

| Domaine | États |
| --- | --- |
| Upload | `PENDING_UPLOAD` → `UPLOADED` |
| Traitement | `WAITING` → `READY` ou `FAILED` |
| Publication | `UNPUBLISHED` → `PUBLISHED` |

La migration V5 initialise toutes les vidéos existantes à `UNPUBLISHED`, avec `published_at` nul. La publication exige `UPLOADED` et `READY`, écrit `PUBLISHED` et la date du serveur dans une transaction, sous verrou de ligne. Une contrainte PostgreSQL protège la cohérence de cet état et de sa date.

La réponse `200`, avec `Cache-Control: no-store`, comprend `id`, `publicationStatus` et `publishedAt`. Les appels répétés ou concurrents retournent la même date. La publication ne modifie pas les dates d'upload ou de traitement, ni la réponse de confirmation `/complete`. Les détails privés et la liste des vidéos renvoient également `publicationStatus` et `publishedAt`.

## Événement Kafka

La même transaction écrit un événement `VideoPublished v2` dans l'outbox. Le relais le publie ensuite dans `video.published.v2`, avec `videoId` comme clé et un `eventId` stable pour les reprises. L'événement contient les références canoniques des compétences et hashtags sélectionnés ; aucune clé S3, URL signée ou donnée d'authentification n'est diffusée. Son schéma versionné est [video-published.v2.schema.json](../contracts/events/video-published.v2.schema.json). Le fil conserve des instantanés dans sa propre base.

Le broker étant séparé de PostgreSQL, la livraison est au moins une fois. Les consommateurs doivent dédupliquer durablement par `eventId`. Une panne Kafka ne bloque pas l'API de publication et le relais réessaie automatiquement.

## Portée de cette étape

L'état et l'événement sont persistés par Video ; l'endpoint ne dépend pas de Kafka pour répondre. Feed consomme l'événement et conserve une projection dédupliquée. `GET /v1/feed?offset=0` expose les publications aux comptes authentifiés. Dans Flutter, le compte peut publier ses vidéos prêtes et parcourir l'onglet « Fil ».

Pour lire une publication, Flutter appelle `GET /v1/videos/{id}/public-playback`. Video vérifie l'état `PUBLISHED` et `READY` avant de générer des URL S3 présignées valables 120 secondes. Le bucket reste privé ; les URL et jetons ne sont pas stockés par le client.

## Vérification

Le changement d'état et l'insertion de l'événement sont exécutés dans une transaction PostgreSQL. Les tests et la CI doivent confirmer le flux Kafka ainsi que l'atomicité avant intégration.
