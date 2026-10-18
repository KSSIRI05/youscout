# Outils, alternatives et recommandations

Recommandations pour YouScout, vérifiées dans la documentation officielle le 17 octobre 2026. « Meilleur » signifie le meilleur compromis pour l'architecture retenue, pas un classement universel. Les compétences de l'équipe restent à confirmer. Aucun outil n'est installé ou provisionné par ce document ; les versions stables compatibles devront être figées lors du socle.

## Stack de référence

| Besoin | Choix recommandé | Alternatives | Pourquoi ce choix ici / quand préférer l'alternative |
| --- | --- | --- | --- |
| Mobile | Flutter / Dart | React Native ; Kotlin/Swift natifs | Choix mobile retenu, UI partagée ; React Native préférable si forte maîtrise React, natif si besoin spécifique dominant |
| Services métier | Java / Spring Boot | FastAPI ; NestJS | Intégrations sécurité, Kafka et données cohérentes pour le système distribué ; FastAPI si équipe surtout Python, NestJS si surtout TypeScript |
| Back-office | React avec TypeScript | Angular ; Flutter Web | Interfaces de gestion et composants web ; Angular si équipe déjà formée et conventions plus strictes |
| Identité | Keycloak | Auth0 ; Cognito ; Firebase Auth | OIDC et fédération contrôlables, déployables hors d'un fournisseur unique ; managé si priorité à réduire l'exploitation |
| API Gateway | Spring Cloud Gateway | Kong ; NGINX | Même écosystème que les services ; Kong si gestion d'API et plugins priment, NGINX pour un proxy plus simple |
| Bus événementiel | Apache Kafka | RabbitMQ ; service cloud de pub/sub | Rejeu et consommateurs indépendants utiles à Feed/Analytics ; RabbitMQ souvent plus simple pour distribuer uniquement des tâches |
| Données relationnelles | PostgreSQL | MySQL | Relations et transactions de User, Rating, Skill et Moderation ; MySQL si expertise/exploitation existante |
| Documents | MongoDB | PostgreSQL JSONB | Choix cible pour Video, Interaction et Chat ; JSONB réduit les moteurs à exploiter mais serait une révision de la persistance retenue |
| Cache et feed | Redis | Valkey ; lecture directe PostgreSQL | Structures adaptées aux vues de feed ; Valkey si stratégie de distribution/licence préférée après contrôle de compatibilité, SQL si petit volume sans cache |
| Recherche avancée | OpenSearch en phase 3 | Recherche PostgreSQL ; Elasticsearch | Index dédiés pour recherche multi-critères ; PostgreSQL suffit au socle, Elasticsearch si l'écosystème requis le justifie |
| Vidéos | S3 privé + CloudFront sur AWS | Stockage compatible S3 + autre CDN | Couple de référence intégré à la cible cloud ; vérifier capacités et sécurité chez chaque fournisseur compatible |
| Transcodage | Workers FFmpeg | MediaConvert ; prestataire vidéo managé | Contrôle du pipeline et démonstration du traitement distribué ; managé si moins d'exploitation est prioritaire |
| Temps réel | WebSocket dans Chat | STOMP ; Socket.IO | Contrat simple contrôlé côté Flutter et Spring ; STOMP si besoin de destinations/broker, Socket.IO impose un client compatible avec son protocole |
| Push | FCM, avec APNs pour iOS | OneSignal ; APNs direct | Envoi Android/iOS intégré ; ce n'est pas un transport d'historique de chat |
| Local | Docker Compose | Installation manuelle | Profils limités au nécessaire ; reproductibilité des dépendances |
| Orchestration | Kubernetes managé | Kubernetes auto-hébergé ; ECS/Cloud Run | Choix cible retenu et déploiements indépendants ; services de conteneurs plus simples si Kubernetes n'est plus un objectif |
| Paquets de déploiement | Helm + Terraform pour infrastructure | Kustomize ; Pulumi | Helm pour paramètres Kubernetes, Terraform pour ressources cloud ; éviter deux outils gérant la même ressource |
| Autoscaling | HPA ; KEDA pour lag Kafka | Nombre fixe de réplicas | Métriques adaptées à chaque charge ; réplicas fixes acceptables pour une démo bornée |
| CI | GitHub Actions + registre GHCR | GitLab CI ; Jenkins | Dépôt déjà sur GitHub ; images versionnées et déploiement staging vérifié |
| Observabilité | OTel + Prometheus + Grafana + Loki + Tempo | Jaeger pour traces ; offre managée | OTel instrumente ; Prometheus métriques, Loki logs, Tempo traces, Grafana visualise ; simplifier par un service managé si nécessaire |
| Tests backend | JUnit, Testcontainers | Tests avec services de test dédiés | Tester réellement PostgreSQL, MongoDB replica set et Kafka ; pas de mocks seuls pour l'atomicité |
| Tests clients et API | flutter_test, integration_test ; Vitest/Testing Library ; OpenAPI | Outils équivalents déjà maîtrisés | Parcours critiques mobile/admin et vérification des contrats |
| Charge | k6 ou Gatling | JMeter | k6 pour scripts HTTP, Gatling si préférence JVM ; mesurer aussi le lecteur vidéo et le lag Kafka |

Le frontend admin peut utiliser TypeScript sans remettre en cause le choix Java des services métier. Éviter de mélanger FastAPI, Spring et NestJS entre domaines au premier MVP : la diversité n'apporte pas encore de bénéfice mesuré.

## Spring Boot ou FastAPI : lequel choisir ?

**Recommandation : Spring Boot pour le backend de référence de YouScout.** Le projet multiplie OIDC/JWT, transactions, Kafka, métriques et bases ; un écosystème cohérent réduit les conventions à inventer. Cette préférence est un jugement d'architecture, pas une preuve de débit supérieur. Spring consomme aussi des ressources et demande une maîtrise Java.

**Préférer FastAPI** si l'équipe est nettement plus productive en Python : excellent choix d'API, OpenAPI et validation, mais préciser les bibliothèques et patterns pour Kafka, outbox, migrations et sécurité. Ne pas conserver deux stacks « au cas où ».

## Kafka ou RabbitMQ : lequel choisir ?

**Kafka reste retenu** parce qu'un changement alimente plusieurs vues et qu'un rejeu est utile. Le projet assume l'exploitation des topics, partitions, rétention et consommateurs. **RabbitMQ serait préférable pour une architecture centrée sur une file de jobs plus simple**, mais son adoption remplacerait ADR-004 et les contrats de rejeu ; ce n'est pas la stack courante du dépôt.

## Choix cloud concret, sans achat

Référence proposée : AWS EKS pour Kubernetes, S3/CloudFront pour médias, RDS PostgreSQL, MSK pour Kafka et une offre MongoDB managée compatible avec les transactions requises (par exemple Atlas après vérification des conditions). Redis peut être managé. Garder les dépendances en réseau privé et valider région, latence, disponibilité des services et coûts avant provisionnement. Ne pas supposer qu'un service « compatible MongoDB » garantit toutes les fonctionnalités MongoDB.

Pour le développement et la validation du MVP, Compose puis un petit cluster Kubernetes de staging sont plus abordables à exploiter. Ils ne démontrent pas une capacité mondiale ni une haute disponibilité de production. Aucun prix ni dimensionnement n'est promis ici.

## Ordre d'apprentissage et d'installation

1. Git, Java/JDK compatible, Maven Wrapper, Flutter/Dart et Android SDK ; Node.js pour React admin. iOS exige l'environnement de compilation Apple approprié.
2. Docker et Compose ; vérifier les conditions de licence des distributions choisies.
3. Keycloak, PostgreSQL, MongoDB replica set, Kafka et stockage compatible S3 dans un environnement de développement défini par le futur Compose.
4. Une API Spring Boot avec authentification, transaction/outbox et tests ; puis la chaîne vidéo FFmpeg.
5. Redis/Feed et mobile ; ensuite modération, chat, notes et dashboard.
6. kubectl, Helm, cluster local de test puis Kubernetes managé ; métriques avant autoscaling.

Ne pas installer toute la stack mondiale sur chaque poste. Définir des profils et mesurer CPU, RAM et disque avant de dimensionner l'environnement de développement.

## Références officielles

- [Flutter : architecture](https://docs.flutter.dev/resources/architectural-overview)
- [Spring Boot : Kafka](https://docs.spring.io/spring-boot/reference/messaging/kafka.html)
- [FastAPI](https://fastapi.tiangolo.com/)
- [Spring Cloud Gateway](https://docs.spring.io/spring-cloud-gateway/reference/)
- [Keycloak : OIDC](https://www.keycloak.org/securing-apps/oidc-layers)
- [Kafka : conception](https://kafka.apache.org/design/)
- [PostgreSQL : schémas et permissions](https://www.postgresql.org/docs/current/ddl-schemas.html)
- [MongoDB : transactions](https://www.mongodb.com/docs/manual/core/transactions/)
- [S3 : destinations des notifications](https://docs.aws.amazon.com/AmazonS3/latest/userguide/notification-how-to-event-types-and-destinations.html)
- [OpenSearch](https://docs.opensearch.org/latest/)
- [Firebase Cloud Messaging](https://firebase.google.com/docs/cloud-messaging)
- [Kubernetes : workloads](https://kubernetes.io/docs/concepts/workloads/)
- [Kubernetes : HPA](https://kubernetes.io/docs/concepts/workloads/autoscaling/horizontal-pod-autoscale/)
- [KEDA : Kafka](https://keda.sh/docs/latest/scalers/apache-kafka/)
- [OpenTelemetry](https://opentelemetry.io/docs/what-is-opentelemetry/)
