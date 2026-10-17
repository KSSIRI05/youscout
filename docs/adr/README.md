# Registre des décisions

Les ADR-001 à ADR-010 expriment l'architecture retenue par le porteur le 17 octobre 2026. « Retenu » désigne une décision de conception, pas une implémentation ou validation de performance. ADR-011 précise la stack recommandée lorsque le texte initial proposait plusieurs outils.

| ADR | Décision | Statut |
| --- | --- | --- |
| [ADR-001](ADR-001-caracteristiques-prioritaires.md) | Caractéristiques prioritaires | Retenu |
| [ADR-002](ADR-002-style-microservices-evenementiel.md) | Microservices orientés événements | Retenu |
| [ADR-003](ADR-003-api-gateway.md) | Points d’entrée des clients | Retenu |
| [ADR-004](ADR-004-event-bus-kafka.md) | Événements via Kafka | Retenu |
| [ADR-005](ADR-005-database-per-service.md) | Propriété des données et persistance polyglotte | Retenu |
| [ADR-006](ADR-006-stockage-et-diffusion-video.md) | Stockage objet, FFmpeg, HLS et CDN | Retenu |
| [ADR-007](ADR-007-feed-cqrs.md) | Feed en CQRS et évolution vers fan-out hybride | Retenu |
| [ADR-008](ADR-008-chat-websocket.md) | Chat isolé en WebSocket | Retenu |
| [ADR-009](ADR-009-authentification-oidc-jwt.md) | Identité OIDC et autorisation JWT | Retenu |
| [ADR-010](ADR-010-conteneurisation-kubernetes.md) | Docker et Kubernetes | Retenu |
| [ADR-011](ADR-011-stack-de-reference.md) | Stack de référence | Recommandé |

## Décisions initiales remplacées

| Ancien ADR | Remplacement |
| --- | --- |
| [0001](0001-architecture-style.md) | ADR-002 et ADR-005 |
| [0002](0002-media-pipeline.md) | ADR-004 et ADR-006 |
| [0003](0003-deployment.md) | ADR-010 |
| [0004](0004-mobile-and-backend-stack.md) | ADR-011 et choix Flutter dans l'architecture |

Créer un nouvel ADR à partir du [modèle](template.md) si une décision retenue change. Conserver la trace et les liens plutôt que réutiliser un identifiant existant.
