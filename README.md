# YouScout - Réseau social de talents du football

Projet ALD 2025/2026, INPT (ASEDS). YouScout permet de publier des vidéos de football, découvrir des talents et échanger avec la communauté.

**État réel : documentation de conception uniquement. Aucun service, client mobile, pipeline CI ou environnement cloud n'est encore implémenté.** L'architecture cible a été retenue par le porteur du projet ; sa validation technique reste à réaliser par les prototypes et tests décrits ici.

## Architecture retenue

Microservices orientés événements derrière une API Gateway, avec Flutter pour le mobile, Kafka pour les événements, S3/CDN pour les médias et Kubernetes pour l'orchestration. La cible comporte 11 services logiques, dont Auth fourni par Keycloak, plus des workers FFmpeg. Le MVP commence avec Auth, User, Video, Feed, Interaction et Skill/Hashtag, sans fusionner leurs responsabilités.

Le backend recommandé est Java / Spring Boot ; l'admin utilise React. PostgreSQL, MongoDB et Redis répondent à des usages différents et restent sous la responsabilité exclusive de chaque service. OpenSearch et le multi-région arrivent après le socle.

## Lire la documentation

| Document | Contenu |
| --- | --- |
| [Projet](docs/PROJECT.md) | Sujet, livrables, périmètre et questions ouvertes |
| [Exigences](docs/REQUIREMENTS.md) | Tous les besoins et leur service responsable |
| [Caractéristiques](docs/QUALITY_ATTRIBUTES.md) | Exigences explicites, déductions et priorités |
| [Architecture](docs/ARCHITECTURE.md) | Comparaison des styles et diagramme de la cible |
| [Explication pas à pas](docs/EXPLICATION_ARCHITECTURE.md) | Comprendre chaque composant avec des exemples |
| [Outils et alternatives](docs/OUTILS_ET_ALTERNATIVES.md) | Choix recommandés, alternatives et compromis |
| [Services et données](docs/SERVICES.md) | Frontières et propriété des données |
| [Contrats et événements](docs/CONTRATS_ET_EVENEMENTS.md) | API, Kafka, outbox et reprise |
| [Sécurité et modération](docs/SECURITE_ET_MODERATION.md) | Authentification, blocage, retrait et fermeture de compte |
| [Roadmap](docs/ROADMAP.md) | MVP, phases suivantes et critères de démonstration |
| [Déploiement](docs/DEPLOYMENT.md) | Compose, Kubernetes et cible cloud |
| [ADRs](docs/adr/README.md) | Dix décisions d'architecture et décisions de stack |
| [Contribution](CONTRIBUTING.md) | Organisation et vérifications |

## Lancer le projet

Il n'existe pas encore de commande de lancement applicatif. L'installation de Flutter, Java ou Docker ne suffit pas à exécuter une application absente. Les versions compatibles, variables d'environnement, migrations, commandes et comptes de démonstration devront être ajoutés et vérifiés lors de l'implémentation. Voir la [roadmap](docs/ROADMAP.md).

Arborescence future, **non encore créée** :

```text
apps/mobile/             Flutter
apps/admin/              React
services/gateway/        Spring Cloud Gateway
services/user/           Spring Boot (idem pour les autres services métier)
workers/transcoding/     FFmpeg et consommateur Kafka
contracts/               OpenAPI et schémas d'événements
infra/                   Compose, Helm, configuration Keycloak et IaC
docs/                    Documentation actuelle
```

## Livraison académique

Le rendu final doit contenir le code source complet, les ADRs, les instructions de lancement et une application déployée et démontrable. Les fonctions reportées après le MVP restent dans le périmètre final obligatoire du sujet. Le dépôt GitHub privé doit être partagé avec les évaluateurs.
