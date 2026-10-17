# ADR-003 : Points d’entrée des clients

Statut : Retenu pour la conception, décision du porteur le 17 octobre 2026. Validation technique à réaliser.

## Contexte

Plusieurs services doivent être accessibles sans exposer toutes leurs adresses publiques.

## Alternatives

Accès direct à chaque service ; Gateway commune ; BFF spécifique par client.

## Décision et justification

Gateway pour les API métier, entrée OIDC pour Auth, entrée WSS pour Chat, upload présigné vers S3 et lecture CDN. Les services restent privés et contrôlent eux-mêmes jetons et autorisations. Pas de BFF supplémentaire au MVP.

## Conséquences et limites

Routage et protections communs ; risque de goulot et point de panne à traiter par réplication/limites. Les exceptions média et identité font partie de la règle, elles ne la contredisent pas.

## Validation et réexamen

Tester contournement de Gateway, contrôle propriétaire, quotas, indisponibilité d’une instance et absence d’exposition des bases.

Voir [architecture](../ARCHITECTURE.md), [outils et sources officielles](../OUTILS_ET_ALTERNATIVES.md) et [roadmap](../ROADMAP.md).
