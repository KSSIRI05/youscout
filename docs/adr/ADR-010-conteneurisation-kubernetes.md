# ADR-010 : Docker et Kubernetes

Statut : Retenu pour la conception, décision du porteur le 17 octobre 2026. Validation technique à réaliser.

## Contexte

Les services doivent être déployables et répliqués indépendamment ; le porteur retient Kubernetes comme cible.

## Alternatives

VM ; Compose seul ; conteneurs managés sans Kubernetes ; Kubernetes managé ou auto-hébergé.

## Décision et justification

Docker pour images, Compose local et Kubernetes managé pour cible cloud ; Helm pour workloads, IaC pour infrastructure. Services réplicables avec données externes, workers bornés, HPA et KEDA après instrumentation. Données/Kafka managés de préférence.

## Conséquences et limites

Exploitation plus complexe qu’un hébergement de conteneurs simple. Kafka et bases restent stateful ; sauvegardes et procédures sont nécessaires. Multi-région ultérieur après choix de réplication et autorité d’écriture ; aucun déploiement effectué par cet ADR.

## Validation et réexamen

Vérifier staging, migrations, rollback, redémarrage d’instance, sauvegarde/restauration et scaling sous charge déclarée.

Voir [architecture](../ARCHITECTURE.md), [outils et sources officielles](../OUTILS_ET_ALTERNATIVES.md) et [roadmap](../ROADMAP.md).
