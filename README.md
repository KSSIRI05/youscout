# YouScout - Réseau social de talents du football

YouScout est une plateforme de découverte des talents du football. Elle permet de partager des vidéos, de mettre en valeur ses compétences et d'échanger avec la communauté.

**État réel : frontend Flutter dans `apps/mobile`, avec démonstration locale, connexion Keycloak, profils, catalogue et parcours d'upload vidéo connectés. Le backend comprend la Gateway, les profils User persistants, le catalogue Skill/Hashtag et le service Video avec préparation et confirmation d'uploads S3, Keycloak et PostgreSQL. Le bucket S3 privé et les rôles IAM dédiés sont provisionnés. Le traitement vidéo, la publication dans le fil, les autres API métier et le back-office restent à développer.** Les workflows CI vérifient le mobile et le backend ; l'architecture cible reste à valider progressivement avec les tests de chaque service. Le parcours Flutter web avec le vrai bucket a été [validé le 28 octobre 2026](docs/validation/video-upload-2026-10-28.md).

## Lancer le socle backend

```sh
python infra/compose/init-env.py
docker compose -f infra/compose/compose.yml up -d --build --wait --wait-timeout 300
python scripts/apply-keycloak.py
python scripts/smoke-backend.py
```

Docker doit être démarré. Le générateur de secrets est exécuté une seule fois. Sur un environnement existant, utiliser `python infra/compose/init-env.py --upgrade` pour ajouter les secrets manquants sans changer les anciens. Lire [le guide backend](docs/BACKEND.md) pour les ports, les tests, Keycloak et les limites, et [le guide Video](services/video/README.md) pour activer le profil Compose Video et l'accès S3 temporaire. Flutter propose aussi un mode connecté pour le profil et le catalogue ; voir [le guide mobile](apps/mobile/README.md).

## Architecture retenue

La chaîne [VideoUploaded → outbox → Kafka](docs/VIDEO_OUTBOX.md) est implémentée et activable via `infra/compose/compose.kafka.yml`. Elle conserve les événements en cas de panne du broker et reprend leur publication après redémarrage. Le worker FFmpeg reste la prochaine étape.

Le [worker d'ingestion](services/media-worker/README.md) consomme ces événements et conserve des tâches `QUEUED` dans sa propre base, avec déduplication durable et acquittement après commit. Il s'active avec `compose.worker.yml` et le profil `worker`. Le téléchargement S3 et l'exécution FFmpeg restent à développer.

Microservices orientés événements derrière une API Gateway, avec Flutter pour le mobile, Kafka pour les événements, S3/CDN pour les médias et Kubernetes pour l'orchestration. La cible comporte 11 services logiques, dont Auth fourni par Keycloak, plus des workers FFmpeg. Le MVP commence avec Auth, User, Video, Feed, Interaction et Skill/Hashtag, sans fusionner leurs responsabilités.

Le backend recommandé est Java / Spring Boot ; l'admin utilise React. PostgreSQL, MongoDB et Redis répondent à des usages différents et restent sous la responsabilité exclusive de chaque service. OpenSearch et le multi-région arrivent après le socle.

## Lire la documentation

| Document | Contenu |
| --- | --- |
| [Compétences et hashtags](docs/SKILL_HASHTAG.md) | Catalogue, administration des compétences et abonnements personnels |
| [Identité et droits](docs/IDENTITE_ET_AUTORISATIONS.md) | Inscription Keycloak, PKCE, rôles user/admin et sessions |
| [Backend local](docs/BACKEND.md) | Lancement du socle, JWT, migration et vérifications |
| [Projet](docs/PROJECT.md) | Vision produit, périmètre et questions ouvertes |
| [Vidéo et S3](services/video/README.md) | Uploads signés, confirmation, persistance et configuration AWS locale |
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

Arborescence actuelle : `apps/mobile/`, `services/gateway/`, `services/user/`, `services/skill/`, `services/video/`, `infra/`, `contracts/openapi/`, `scripts/`, `docs/` et les workflows mobile/backend. Les autres services, `apps/admin/` et `workers/` restent à créer.

## Livraison du produit

Chaque livraison doit fournir le code source, les décisions d'architecture, des instructions de lancement vérifiées et les résultats des tests. La roadmap distingue le MVP, les fonctionnalités suivantes et la préparation du lancement public.
