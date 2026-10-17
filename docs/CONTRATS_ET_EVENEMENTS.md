# Contrats API et événements

Document de conception. Le seul endpoint actuellement implémenté est le diagnostic JWT décrit dans [user-foundation.yaml](../contracts/openapi/user-foundation.yaml). Les API métier et les contrats Kafka restent à réaliser.

## API

REST/JSON avec OpenAPI et préfixe `/v1`, pagination par curseur pour feed/commentaires, erreurs structurées et identifiant de corrélation. Utiliser une clé d'idempotence pour création d'upload et commandes rejouables. Les lectures ne doivent pas avoir d'effet métier. Définir explicitement timeout, statut de traitement asynchrone et limites de taille. gRPC est une alternative interne à introduire seulement si une mesure le justifie.

## Enveloppe d'événement proposée

```json
{
  "eventId": "uuid",
  "eventType": "VideoPublished",
  "schemaVersion": 1,
  "aggregateId": "video-id",
  "aggregateVersion": 3,
  "occurredAt": "2026-10-17T12:00:00Z",
  "producer": "video-service",
  "traceId": "trace-id",
  "payload": {"videoId": "video-id", "authorId": "user-id"}
}
```

Pas de binaire vidéo, jeton d'accès ou mot de passe dans Kafka. Minimiser les données personnelles et borner la rétention. Le journal d'événements n'est pas une archive publique permanente des profils supprimés.

| Topic proposé | Clé | Producteur | Consommateurs |
| --- | --- | --- | --- |
| `video.lifecycle.v1` | videoId | Video | Feed, analytics, indexeur, notification |
| `media.processing.requests.v1` | videoId | Relais outbox Video | Groupe workers FFmpeg |
| `media.processing.results.v1` | videoId | Workers | Video |
| `interaction.events.v1` | videoId | Interaction | Feed, analytics, notification |
| `user.events.v1` | userId | User | Feed, Chat, analytics, indexeur |
| `rating.events.v1` | videoId | Rating | Feed, analytics |
| `catalog.events.v1` | skillId ou hashtagId | Skill/Hashtag | Video, Feed, indexeur |
| `moderation.commands.v1` | targetId | Moderation | Propriétaire ciblé, filtré par type |
| `moderation.results.v1` | targetId | Propriétaire ciblé | Moderation, Feed, indexeur, analytics |
| `account.lifecycle.v1` | userId | User/orchestrateur | Services concernés, adaptateur Auth |

Un groupe distinct par projection indépendante reçoit chaque événement ; les instances d'un même groupe se répartissent le travail. Pour les groupes classiques retenus ici, le nombre de partitions borne le parallélisme utile. L'ordre n'est garanti qu'au sein d'une partition ; clé stable et version d'agrégat servent à gérer doublons et événements tardifs. Des topics séparés n'offrent pas d'ordre global commun.

## Livraison, outbox et échecs

1. Transaction locale : mutation et entrée outbox.
2. Relais par polling au MVP ; CDC avec Debezium envisageable ensuite. Un simple listener HTTP vers Kafka ne remplace pas l'atomicité.
3. Consommateur : vérifier eventId et version, écrire effet et déduplication atomiquement, puis acquitter. Pour Redis, appliquer une opération atomique adaptée et un mécanisme durable de reconstruction.
4. Retenter avec backoff borné ; envoyer les messages irrécupérables dans un topic d'échec, alerter, inspecter et rejouer de façon contrôlée.

Les garanties transactionnelles Kafka ne couvrent pas automatiquement MongoDB, S3, PostgreSQL ou un push externe. Les workers utilisent une clé de job et un préfixe objet déterministes, des leases ou états de job pour les traitements longs ; acquittement et reprise doivent éviter un traitement concurrent incontrôlé pendant les rééquilibrages.

## Projections et rejeu

CQRS sépare les écritures propriétaires et les vues Feed/Analytics/recherche. Ce choix n'impose pas l'event sourcing des services. Rejouer les événements encore retenus ; au-delà, reconstruire depuis un snapshot/export versionné des propriétaires puis rattraper les événements. Ne jamais ressusciter un contenu supprimé après un rejeu : appliquer états de retrait et versions. Documenter suppression, rétention et checkpoints avant lancement.

Référence : [conception Kafka](https://kafka.apache.org/design/).
