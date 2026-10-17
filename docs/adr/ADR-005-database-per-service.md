# ADR-005 : Propriété des données et persistance polyglotte

Statut : Retenu pour la conception, décision du porteur le 17 octobre 2026. Validation technique à réaliser.

## Contexte

Les domaines doivent évoluer sans dépendre des tables et migrations d’un autre service.

## Alternatives

Base et schéma partagés ; PostgreSQL partout ; moteurs par usage avec propriété exclusive.

## Décision et justification

PostgreSQL pour Auth, User, Rating, Skill, Moderation, Notification et Analytics ; MongoDB pour Video, Interaction et Chat ; Redis pour caches/projections propriétaires ; S3 pour binaires ; OpenSearch pour recherche dérivée. Mutualisation physique possible avec droits et bases distincts.

## Conséquences et limites

Pas de jointures ou écritures directes inter-services. Plus de moteurs à sauvegarder et surveiller. MongoDB et outbox exigent une stratégie atomique, avec replica set si transactions multi-documents. Redis et OpenSearch se reconstruisent ; aucun n’est l’archive métier unique.

## Validation et réexamen

Tester permissions croisées refusées, migrations indépendantes, restauration par domaine et reconstruction des projections.

Voir [architecture](../ARCHITECTURE.md), [outils et sources officielles](../OUTILS_ET_ALTERNATIVES.md) et [roadmap](../ROADMAP.md).
