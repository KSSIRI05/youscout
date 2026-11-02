# Service Feed

Feed consomme aussi `user.relationships.v1`. User écrit suivis et blocages dans son outbox au sein de la transaction de commande ; la migration V4 alimente d'abord les relations existantes puis publie un marqueur de fin. Feed renvoie temporairement 503 jusqu'au traitement du marqueur. Redis conserve les ensembles projetés ainsi qu'un cache de pages indexé par versions de relations et de publications ; un changement fait pointer les lectures vers une nouvelle version sans servir les anciennes pages.

La projection de relations est éventuelle et le topic local ne comporte qu'une partition pour préserver l'ordre du snapshot et des commandes dans ce MVP. Redis utilise un volume AOF local. Une perte complète du volume nécessite une procédure opérateur de rejeu/reconstruction avant de rouvrir Feed ; ce runbook et le fan-out des publications dans des timelines individuelles restent à livrer.

Feed consomme le contrat [`VideoPublished v2`](../../contracts/events/video-published.v2.schema.json) depuis `video.published.v2`. Il valide l'enveloppe, les références catalogue et la clé Kafka, puis conserve une projection locale des vidéos publiées et des étiquettes dans sa base PostgreSQL dédiée.

L'inbox PostgreSQL déduplique durablement les messages par `eventId` et conserve une empreinte du contenu. Une réutilisation conflictuelle d'identifiant ou une seconde publication divergente pour la même vidéo est mise en quarantaine avec les coordonnées Kafka, sans enregistrer le payload. Les offsets Kafka sont commités uniquement après l'écriture métier ou la quarantaine ; une panne de base provoque donc une reprise.

`GET /v1/feed?offset=0` renvoie une page de 20 publications, triées par date décroissante, avec `hasMore`, le sujet public de l'auteur et les références de catalogue. L'endpoint exige un JWT portant le rôle `user` ou `admin`, renvoie `Cache-Control: no-store` et ne révèle aucune clé S3. Le contrat est décrit dans [`contracts/openapi/feed.yaml`](../../contracts/openapi/feed.yaml).

La lecture d'une entrée s'effectue via `GET /v1/videos/{id}/public-playback`. Video vérifie que la publication existe et que le média est prêt, puis renvoie des URL S3 présignées de courte durée. Flutter présente le fil dans l'onglet « Fil », permet de parcourir les pages et ouvre la vidéo avec ce point d'accès public authentifié.

Le service démarre avec Compose en activant le profil `feed` et en incluant `compose.kafka.yml`. Le secret local `FEED_DB_PASSWORD` est ajouté par `python infra/compose/init-env.py --upgrade`.
