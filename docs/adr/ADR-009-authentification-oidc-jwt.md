# ADR-009 : Identité OIDC et autorisation JWT

Statut : Retenu pour la conception, décision du porteur le 17 octobre 2026. Validation technique à réaliser.

## Contexte

Comptes tiers et administration exigent une identité centrale et des droits vérifiés au niveau des ressources.

## Alternatives

Authentification maison ; Keycloak ; fournisseur OIDC managé.

## Décision et justification

Keycloak pour Auth ; Authorization Code et PKCE pour Flutter, sans secret embarqué. Rôles user/admin, rôle scout éventuel à valider. Chaque service valide jeton et droits, avec contrôle du propriétaire. Workflow explicite pour désactivation et fermeture.

## Conséquences et limites

Moins de logique sensible maison mais Keycloak et ses données doivent être exploités/sauvegardés. JWT court ne garantit pas une révocation instantanée ; vérifier état sensible et couper les sockets lorsque nécessaire. Pas de jetons ni mots de passe dans Kafka/logs.

## Validation et réexamen

Tester signature/issuer/audience invalides, rôle manquant, accès à la ressource d’autrui, suspension et fédération tiers.

Voir [architecture](../ARCHITECTURE.md), [outils et sources officielles](../OUTILS_ET_ALTERNATIVES.md) et [roadmap](../ROADMAP.md).
