# ADR-004 : Événements via Kafka

Statut : Retenu pour la conception, décision du porteur le 17 octobre 2026. Validation technique à réaliser.

## Contexte

Un changement doit alimenter Feed, analytics, recherche et notifications, avec possibilité de rattrapage/rejeu.

## Alternatives

Appels REST en chaîne ; RabbitMQ ; Kafka ; pub/sub managé.

## Décision et justification

Kafka avec topics versionnés, clés d’agrégat, groupes de consommateurs indépendants, outbox transactionnelle et consommateurs idempotents. Relais polling au MVP ; CDC optionnel ensuite. Jobs média et événements métier ont des topics distincts.

## Conséquences et limites

Rejeu et découplage, mais partitions/rétention/lag à exploiter. Ordre par partition uniquement ; pas de transaction globale automatique avec S3 ou les bases. Messages d’échec suivis et rejouables. RabbitMQ serait plus simple pour une seule file de tâches.

## Validation et réexamen

Simuler broker indisponible, doublons, événements inversés et crash avant/après acquittement. Vérifier outbox locale et reprise des longs jobs FFmpeg.

Voir [architecture](../ARCHITECTURE.md), [outils et sources officielles](../OUTILS_ET_ALTERNATIVES.md) et [roadmap](../ROADMAP.md).
