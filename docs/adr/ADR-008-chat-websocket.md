# ADR-008 : Chat isolé en WebSocket

Statut : Retenu pour la conception, décision du porteur le 17 octobre 2026. Validation technique à réaliser.

## Contexte

Le produit prévoit des messages instantanés ; les connexions longues ont un profil distinct des lectures du feed.

## Alternatives

Polling ; SSE pour réception et REST pour envoi ; WebSocket ; service temps réel managé.

## Décision et justification

Entrée WSS authentifiée vers Chat ; MongoDB pour historique propriétaire ; message persisté avant accusé d’acceptation ; identifiant client de déduplication et séquence de conversation. Diffusion inter-instances par mécanisme dédié à préciser, par exemple Redis Pub/Sub pour connexions actives avec reprise depuis MongoDB.

## Conséquences et limites

Reconnexion, backpressure et révocation à gérer. Redis Pub/Sub ne garantit pas l’historique durable. Kafka pour événements métier n’est pas à lui seul un routage vers chaque socket. Accepté, livré et lu sont des états distincts.

## Validation et réexamen

Tester reconnexion, doublons, destinataire hors ligne, plusieurs instances, blocage et suspension d’un utilisateur déjà connecté.

Voir [architecture](../ARCHITECTURE.md), [outils et sources officielles](../OUTILS_ET_ALTERNATIVES.md) et [roadmap](../ROADMAP.md).
