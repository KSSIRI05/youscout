# Déploiement cible

Plan uniquement : aucune ressource cloud, image ou configuration exécutable n'a été créée. Docker pour le packaging, Compose pour le développement et Kubernetes pour la cible retenue. Un cluster local sert à apprendre et tester, pas à prouver la disponibilité de production.

## Environnements

| Environnement | Périmètre |
| --- | --- |
| Développement Compose | Profils par chantier ; Keycloak, Kafka, PostgreSQL, MongoDB replica set, stockage objet et services nécessaires |
| Intégration Kubernetes | Manifests/Helm, routage, secrets, probes et tests de panne |
| Staging cloud | Kubernetes managé, stockage privé/CDN et dépendances adaptées à la démo |
| Production envisagée | Réplicas, répartition entre zones, sauvegardes et objectifs de reprise validés |

Référence cloud recommandée : EKS, S3/CloudFront, RDS PostgreSQL, MSK et MongoDB managé compatible avec les transactions retenues. La région, les offres et le dimensionnement restent à comparer et confirmer ; aucune commande de provisionnement ou dépense n'est autorisée par ce plan documentaire.

## Ressources Kubernetes

- Deployments pour Gateway, services métier réplicables, relais et workers ; Keycloak avec sa base externe et la configuration adaptée.
- Bases/Kafka managés de préférence pour la cible ; si hébergés dans le cluster, operators/StatefulSets, volumes, sauvegardes et procédures propres. Ne pas les déclarer stateless.
- Service interne par composant ; seule l'entrée HTTP/TLS, OIDC et WSS est exposée. S3/CDN suivent des autorisations séparées.
- Requests/limits, readiness/liveness et arrêt gracieux ; migration contrôlée avant rollout compatible.
- HPA pour services selon CPU/métriques utiles ; KEDA pour workers selon lag Kafka, borné par partitions et ressources disponibles.
- Secrets externes, identités de workloads et droits minimaux ; NetworkPolicies avec moteur réseau qui les applique.
- Helm pour les déploiements ; Terraform pour les ressources cloud, sans propriété concurrente des mêmes objets.

## Médias

S3 privé, URL d'upload courte et clé limitée, multipart si besoin validé. Video vérifie la complétion ; notifications S3 vers SQS/EventBridge puis adaptateur possible, jamais S3 -> Kafka supposé natif. Workers FFmpeg isolés, résultats idempotents, temporaires nettoyés et objets orphelins réconciliés. CDN avec origine privée, signatures courtes et procédure de retrait testée.

## Livraison et exploitation

CI GitHub Actions : lint et tests, tests de contrats/intégration, build d'images, scan, publication GHCR, déploiement staging puis smoke tests. Le pipeline applicatif reste à écrire. Configurer OpenTelemetry, Prometheus, Grafana, Loki et Tempo ; distinguer le dashboard d'exploitation de celui des administrateurs produit.

Alertes sur erreurs et latence API, lag Kafka, outbox bloquée, transcodage échoué, refus d'accès et espace disque. Protéger logs et endpoints de métriques. Définir RPO/RTO, rétention et tests de restauration pour chaque stockage ; ne pas annoncer de valeurs garanties non vérifiées.

Pour le multi-région ultérieur, choisir autorité d'écriture, réplication, placement et comportement en partition. Un CDN mondial n'est pas un backend actif-actif mondial.

## Runbook à produire avec le code

Versions/digests, ressources, variables et secrets requis, migrations, lancement/arrêt, domaines et callbacks OIDC, sauvegarde/restauration, rollback, rotation des clés et tests mobiles/admin. Le README ne doit contenir que des commandes vérifiées.

Références : [Kubernetes workloads](https://kubernetes.io/docs/concepts/workloads/), [HPA](https://kubernetes.io/docs/concepts/workloads/autoscaling/horizontal-pod-autoscale/), [notifications S3](https://docs.aws.amazon.com/AmazonS3/latest/userguide/notification-how-to-event-types-and-destinations.html).
