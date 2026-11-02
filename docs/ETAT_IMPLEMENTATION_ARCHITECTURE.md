# Etat de l'implementation par rapport a l'architecture cible

Date de reference : 2 novembre 2026.

L'architecture cible reste celle de [ARCHITECTURE.md](ARCHITECTURE.md). Ce tableau distingue les services et flux deja utilisables des choix de persistance qui n'ont pas encore ete portes dans le code. Les ecarts sont des jalons ouverts, pas des changements silencieux de la cible.

| Domaine cible | Implementation actuelle | Reste pour la cible |
| --- | --- | --- |
| Auth, Gateway, User | Keycloak, JWT, Gateway, profils, suivis, blocages persistants et outbox Kafka de relations | Durcissement multi-replicas de l'ordre de relais, blocage applique aux interactions et fermeture de compte |
| Skill/Hashtag | Catalogue, recherche, suivi de hashtags | Outbox/evenements catalogue et interface admin |
| Video et media-worker | MongoDB en replica set pour metadonnees, etats et outbox ; upload S3 prive, traitement FFmpeg, publication | Migrer les tests d'integration, HLS, CDN prive, reconciliation et suppression des artefacts |
| Feed | Projection VideoPublished, projection Redis des suivis/blocages, cache de pages versionne | Fan-out des publications par utilisateur, retrait de contenus, reconstruction automatisee de Redis |
| Interaction, Chat, Moderation | Social agrege likes/commentaires/signalements/messages persistants | Frontieres separees, validation d'acces a la video, moderation admin et chat WebSocket |
| Rating | Absent | Service, regles de notation et persistance PostgreSQL |
| Notification | Absent | Service, appareils et push FCM/APNs |
| Admin/Analytics | Absent | Dashboard, projections et mesures definies |
| Exploitation | Compose local, CI, S3 Terraform, Kafka local | Kubernetes/staging, OTel/metrics/logs/traces, sauvegardes, restauration et charge |

## Decisions de persistance

La cible conserve PostgreSQL pour User, Skill, Rating, Moderation, Notification et Analytics ; MongoDB pour Video, Interaction et Chat ; Redis pour les projections Feed et compteurs Interaction ; S3 pour les binaires ; OpenSearch pour la recherche derivee.

Le code utilise PostgreSQL pour User, Skill, Feed, Social et le worker. Video utilise MongoDB en replica set ; PostgreSQL reste disponible pour l'import/sauvegarde. Redis porte les relations projetees et un cache de pages Feed versionne, tandis que la projection durable des publications reste dans PostgreSQL. Social n'a pas encore migre vers MongoDB.

## Fil personnalise

Feed projette les publications et les relations via Kafka. User persiste chaque changement avec son outbox transactionnel ; un backfill termine par un marqueur avant que Feed serve des pages. Redis conserve les ensembles de relations et met en cache les pages par versions de relation/publication. Une modification invalide les pages logiquement en incrementant les versions ; les anciennes cles expirent. La projection est eventuelle et doit etre reconstruite si son volume Redis est perdu. La lecture video reste autorisee par Video, independamment de cette projection.
