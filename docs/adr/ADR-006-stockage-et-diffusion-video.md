# ADR-006 : Stockage objet, FFmpeg, HLS et CDN

Statut : Retenu pour la conception, décision du porteur le 17 octobre 2026. Validation technique à réaliser.

## Contexte

Les vidéos sont volumineuses ; transformation et diffusion ne doivent pas monopoliser les API métier.

## Alternatives

Binaire en base ; upload proxifié ; upload direct et workers ; prestataire vidéo managé.

## Décision et justification

Upload court présigné vers stockage S3 privé ; confirmation contrôlée par Video et outbox ; workers FFmpeg produisent HLS/miniatures ; résultat validé par Video avant publication ; CDN privé avec accès autorisé. Notifications objet optionnelles via adaptateur explicite.

## Conséquences et limites

Traitement asynchrone et CDN réduisent la charge API mais ajoutent états, orphelins et échecs à réconcilier. Les signatures déjà émises ont une fenêtre de validité. FFmpeg isolé et borné. S3 n’écrit pas directement dans Kafka.

## Validation et réexamen

Tester média invalide, upload interrompu, double notification, crash worker, retrait pendant traitement et expiration des autorisations.

Voir [architecture](../ARCHITECTURE.md), [outils et sources officielles](../OUTILS_ET_ALTERNATIVES.md) et [roadmap](../ROADMAP.md).
