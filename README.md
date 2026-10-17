# YouScout - Réseau social de talents du football

Projet ALD 2025/2026, INPT (ASEDS). YouScout permet de publier des vidéos de football, découvrir des talents et échanger avec la communauté.

**État réel : première version du frontend Flutter dans `apps/mobile`, avec démonstration locale interactive et workflow CI mobile. Le backend, l’admin et l’infrastructure cloud restent à implémenter.** L'architecture cible a été retenue par le porteur du projet ; sa validation technique reste à réaliser par les prototypes et tests décrits ici.

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

Le frontend utilise Flutter **3.47.7** / Dart **3.13.5** (version de validation et de CI).

```sh
cd apps/mobile
flutter pub get
flutter run -d web-server --web-port 7357
```

Ouvrir ensuite l’adresse affichée dans le navigateur. Les cibles Android et iOS sont générées mais nécessitent leurs SDK et une validation sur appareil.

```sh
flutter analyze
flutter test
flutter build web --release
```

La démo couvre le fil, la découverte, les profils, les interactions, la publication et les messages en mémoire. Les données disparaissent au rechargement. La sélection vidéo est locale ; aucun upload, transcodage, serveur, authentification ou envoi de message distant n’est effectué. Voir [le guide mobile](apps/mobile/README.md) et [l’état du frontend](docs/FRONTEND.md).

La [maquette Figma](https://www.figma.com/design/SZIskR01SeNgOsSrAwiB9p) guide les couleurs, composants et parcours.

Arborescence actuelle : `apps/mobile/`, `docs/` et `.github/workflows/mobile.yml`. Les dossiers `apps/admin/`, `services/`, `workers/`, `contracts/` et `infra/` restent à créer.

## Livraison académique

Le rendu final doit contenir le code source complet, les ADRs, les instructions de lancement et une application déployée et démontrable. Les fonctions reportées après le MVP restent dans le périmètre final obligatoire du sujet. Le dépôt GitHub privé doit être partagé avec les évaluateurs.
