# ADR-002 : Microservices orientés événements

Statut : Retenu pour la conception, décision du porteur le 17 octobre 2026. Validation technique à réaliser.

## Contexte

Video, Chat, Feed et interactions ont des cycles et charges différents. Le porteur retient une cible distribuée plutôt que la proposition initiale à quatre services.

## Alternatives

Monolithe en couches ; monolithe modulaire avec workers ; service-based ; serverless ; événements seuls ; space-based ; microservices avec REST et événements.

## Décision et justification

Onze services logiques, Auth fourni par Keycloak, API Gateway et Kafka ; REST pour échanges immédiats, événements pour propagation. Livrer six services au socle puis les autres domaines. La grille pondérée de ARCHITECTURE est qualitative et inclut les compromis.

## Conséquences et limites

Isolation et déploiement indépendant possibles, mais disponibilité non automatique. Outbox, contrats et observabilité deviennent nécessaires. Un monolithe répliqué reste une alternative valable ; il est écarté ici pour le degré d’indépendance souhaité.

## Validation et réexamen

Démontrer la chaîne vidéo et une panne de worker/chat sans rupture des lectures indépendantes. Vérifier les dépendances réelles avant toute affirmation de résilience.

Voir [architecture](../ARCHITECTURE.md), [outils et sources officielles](../OUTILS_ET_ALTERNATIVES.md) et [roadmap](../ROADMAP.md).
