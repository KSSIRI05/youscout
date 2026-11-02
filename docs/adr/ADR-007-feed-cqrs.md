# ADR-007 : Feed en CQRS et évolution vers fan-out hybride

Statut : Retenu pour la conception, décision du porteur le 17 octobre 2026. Validation technique à réaliser.

## Contexte

Le feed est très lu et doit intégrer publications, abonnements, blocages et retraits sans jointures distribuées coûteuses.

## Alternatives

Calcul complet à la lecture ; fan-out à l’écriture ; fan-out à la lecture ; combinaison hybride.

## Décision et justification

Vue de lecture Redis alimentée par événements. Au MVP, chronologie et découverte simple ; cible hybride pré-calculant les auteurs ordinaires et fusionnant les grands comptes à la lecture. Définir le seuil par mesures. Reconstruction avec événements retenus et snapshots propriétaires.

## Conséquences et limites

Cohérence à terme ; le filtrage sensible doit vérifier les autorisations récentes. CQRS ne signifie pas event sourcing complet ni conservation infinie Kafka. La diversité du classement nécessite des critères produit et des mesures d’exposition.

## Validation et réexamen

Tester perte Redis, rejeu sans résurrection des contenus retirés, lag, grands comptes et distribution de visibilité des nouveaux talents.

Voir [architecture](../ARCHITECTURE.md), [outils et sources officielles](../OUTILS_ET_ALTERNATIVES.md) et [roadmap](../ROADMAP.md).

## Local implementation status (2026-11-02)

User writes follow/block changes to a transactional outbox and publishes `UserRelationChanged v1`. Feed consumes the ordered relationship stream into Redis sets. Existing rows are backfilled through a `UserRelationshipsSnapshotComplete` marker; Feed returns 503 until it sees that marker. Redis also caches response pages using relation and publication versions, so old pages become unreachable after a relation or publication change. PostgreSQL remains the durable VideoPublished projection and recovery source for publication metadata. Redis relation-set restoration after volume loss and per-user video fan-out remain operational follow-ups; private playback authorization is still checked by Video.
