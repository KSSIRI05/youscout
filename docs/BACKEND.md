# Socle backend — développement local

Pour comprendre le code commenté et les migrations, consulter [Lire le backend](LECTURE_BACKEND.md).

Cette itération ajoute Java 21, Spring Boot 4.0.8, Spring Cloud 2025.1.3, Keycloak 26.7.3 et PostgreSQL 17.11. Les versions Maven sont fixées, ainsi que les tags des images. Les tags Docker ne sont pas verrouillés par digest.

## Composants réellement implémentés

- `services/video` : Keycloak, préparation idempotente d'uploads S3 et confirmation (`POST /v1/videos/{id}/complete`), avec métadonnées PostgreSQL et propriétaire issu du `sub`. La confirmation vérifie le fichier avant l'état `UPLOADED` et écrit `VideoUploaded` dans une outbox transactionnelle. Un relais optionnel publie dans Kafka ; voir [le guide outbox](VIDEO_OUTBOX.md) et [le guide Video](../services/video/README.md). Compose démarre Video avec sa base via le profil `video`. La validation du média et le worker restent à développer.

- `services/gateway` : Spring Cloud Gateway WebFlux, route `/v1/users/**`, contrôles JWT, CORS local et identifiant `X-Request-Id` borné et propagé. Aucun appel aux bases.
- `services/user` : Spring MVC, validation indépendante des JWT, profils personnels persistants via JDBC et migration Flyway `user_profiles`.
- `services/skill` : [catalogue Skill/Hashtag](SKILL_HASHTAG.md), administration des compétences et abonnements personnels.
- `infra/compose` : trois processus PostgreSQL avec utilisateurs et volumes distincts, Keycloak et les trois applications Java.
- `infra/keycloak` : realm `youscout`, rôles `user`/`admin`, client public `youscout-mobile` avec Authorization Code + PKCE S256 obligatoire et audience `youscout-api`.
- Contrat de diagnostic dans `contracts/openapi/user-foundation.yaml`.

`GET /v1/users/session` reste un diagnostic sans écriture. `GET /v1/users/me` et `PATCH /v1/users/me` gèrent désormais le profil persistant du sujet authentifié. Le mode connecté Flutter utilise ces API ; la démo sociale reste indépendante et locale. Voir [FRONTEND.md](FRONTEND.md).

## Profil personnel persistant

`GET /v1/users/me` crée le profil à la première consultation authentifiée, puis renvoie la ligne existante sans réécrire les champs ou les dates. Cette création paresseuse est une exception explicitement choisie pour ce parcours : ce GET peut insérer une ligne lors du premier appel. Le nom initial provient du claim `name`, à défaut `preferred_username`, puis « Joueur » ; il est borné à 80 caractères Unicode. La biographie initiale est vide. Des claims Keycloak ultérieurement modifiés n’écrasent pas un profil existant.

`PATCH /v1/users/me` accepte un objet JSON partiel, par exemple :

```json
{"displayName": "Yassine Amrani", "biography": "Ailier à Casablanca"}
```

- `displayName` : chaîne non nulle, non vide après suppression des espaces aux extrémités, au plus 80 points de code Unicode.
- `biography` : chaîne non nulle, au plus 500 points de code Unicode ; `""` efface la biographie.
- Un champ omis reste inchangé. Un objet vide, `null`, un autre type ou un champ inconnu entraîne HTTP 400 avec `application/problem+json`.
- `subject`, `createdAt` et `updatedAt` sont contrôlés par le serveur. Le seul identifiant utilisé dans les requêtes SQL est le `sub` du JWT vérifié. Ni paramètre de requête, ni corps, ni en-tête d’identité client ne sélectionnent un autre profil.
- Un PATCH valide peut aussi créer le profil s’il n’existe pas ; une requête invalide ne crée rien. La création et la modification sont transactionnelles.

La réponse HTTP 200 contient `subject`, `displayName`, `biography`, `createdAt` et `updatedAt`, avec `Cache-Control: no-store`. `createdAt` reste stable ; `updatedAt` change lors d’un PATCH valide. Un `sub` absent, vide ou trop long est refusé par HTTP 401 sur ces endpoints.

L’insertion `ON CONFLICT DO NOTHING` évite les doublons à la première utilisation simultanée. Le PATCH applique un UPDATE des seuls champs fournis : des modifications concurrentes de champs distincts sont conservées ; pour un même champ, la dernière mise à jour SQL gagne. Aucun contrôle de version optimiste n’est encore exposé.

La migration V1 existante contient déjà les colonnes nécessaires : elle est conservée telle quelle, sans réécriture d’une migration appliquée. Les événements de profil et l’outbox restent à implémenter avant d’alimenter d’autres services.

## Lancer avec Docker Compose

Depuis la racine, avec Docker Engine/Docker Desktop démarré, Compose v2 et Python 3 :

```sh
python infra/compose/init-env.py
docker compose -f infra/compose/compose.yml config --quiet
docker compose -f infra/compose/compose.yml up -d --build --wait --wait-timeout 300
python scripts/apply-keycloak.py
python scripts/smoke-backend.py
python scripts/smoke-identity.py
```

Pour un environnement déjà initialisé, exécuter `python infra/compose/init-env.py --upgrade` avant Compose ; cela ajoute le secret Skill sans changer les anciens.

Le générateur crée `infra/compose/.env` ignoré par Git, avec des mots de passe aléatoires. Il refuse d’écraser un fichier existant. Ne pas le relancer à chaque démarrage. Compose charge ce fichier depuis le dossier du fichier Compose. Les valeurs de `.env.example` sont des indications, pas des secrets utilisables.

| Accès local | Adresse |
| --- | --- |
| Gateway et santé | http://localhost:8080/actuator/health |
| Keycloak, console admin | http://localhost:8180/admin/ |
| Découverte OIDC | http://localhost:8180/realms/youscout/.well-known/openid-configuration |

L’administrateur Keycloak s’appelle `admin` ; son mot de passe est dans le fichier `.env` local. L’inscription est activée dans Keycloak et donne uniquement user. Aucun compte joueur ni mot de passe de démonstration n’est committé. Les ports publiés écoutent uniquement sur `127.0.0.1`. Le service User et les bases ne publient aucun port sur l’hôte.

```sh
docker compose -f infra/compose/compose.yml ps
docker compose -f infra/compose/compose.yml logs --tail 100 gateway user
docker compose -f infra/compose/compose.yml down
```

`down` arrête les conteneurs et conserve les volumes. Les mots de passe des bases s’appliquent à leur première initialisation : changer `.env` ne change pas les mots de passe de volumes existants. L’import du realm se fait à sa création ; les modifications du JSON ne réécrivent pas automatiquement un realm déjà importé. Planifier une migration explicite des données/configurations existantes.

## Identité et réseau

L’issuer reste `http://localhost:8180/realms/youscout`, adresse vue par le navigateur. Dans Docker, les applications obtiennent les clés via `http://keycloak:8080/.../certs`, mais continuent de vérifier cet issuer externe. Elles vérifient la signature RS256, les dates du jeton, l’issuer et l’audience `youscout-api` ; un jeton valide pour une autre API est refusé. La Gateway transmet le Bearer et User le valide de nouveau. Les en-têtes de rôle/identité fournis par le client ne sont pas une source d’autorisation.

Le client mobile autorise exactement les callbacks `http://localhost:7357/auth/callback`, `http://127.0.0.1:7357/auth/callback` et `ma.youscout://oauth/callback`. Flutter doit encore implémenter ces callbacks et le stockage de session. Aucun secret client mobile, flux implicite ou password grant n’est activé.

Le client confidentiel `youscout-smoke` est réservé à la vérification locale : client credentials, sans connexion interactive, secret aléatoire passé à Keycloak par environnement. Le script récupère un vrai jeton et teste Gateway → User ainsi que la lecture/modification de son propre profil technique, sans afficher jeton ni secret. Cela ne prouve pas une connexion de joueur. Ce client et le mode `start-dev` ne constituent pas une configuration de production.

Les profils exigent user ou admin ; le diagnostic admin exige admin. Seule la santé est publique, sans détails internes. CORS permet les deux origines locales sur le port 7357, sans cookies. L’admin métier et les règles de propriété des autres domaines restent à implémenter ; les profils personnels utilisent déjà le sub du jeton. Les rôles user/admin sont désormais contrôlés dans Gateway et User ; voir la [matrice des droits](IDENTITE_ET_AUTORISATIONS.md). Le diagnostic admin ne remplace pas le futur produit d’administration. La readiness de User inclut la disponibilité de sa base.

## Compiler et tester

JDK 21 et Docker sont nécessaires pour l’ensemble des tests. Le wrapper télécharge Maven 3.9.11.

```sh
./mvnw -B -ntp verify
```

Sous PowerShell :

```powershell
.\mvnw.cmd -B -ntp verify
```

Les tests démarrent de vrais serveurs HTTP, vérifient des JWT signés avec des clés RSA de test, puis contrôlent les refus pour mauvaise signature, issuer, audience et expiration. Gateway couvre le routage, la propagation du jeton et du request-id, et CORS. User utilise un PostgreSQL éphémère Testcontainers et vérifie la migration, la création unique, la persistance, le PATCH partiel, les limites, les requêtes invalides, l’isolation entre utilisateurs et les accès concurrents. Les conteneurs de test sont nettoyés automatiquement. Ils n’utilisent pas les bases persistantes Compose.

Les tests Java couvrent 12 scénarios Gateway, 21 scénarios User et 16 scénarios Skill, soit 49 au total ; trois tests Python couvrent la création et la mise à jour non destructive des secrets. Le smoke test a aussi été validé avec un jeton émis par Keycloak, y compris la conservation du profil après redémarrage du service User. Le frontend dispose de tests de session et d’interface ; la validation sur appareils Android/iOS reste à réaliser.

La CI `backend.yml` exécute les tests, construit les images, démarre Compose et lance le smoke test Keycloak réel. Aucun déploiement cloud n’est configuré.

Pour vérifier la conservation du profil technique et des abonnements après redémarrage de User et Skill :

```sh
python scripts/smoke-backend.py
docker compose -f infra/compose/compose.yml restart user skill
docker compose -f infra/compose/compose.yml up -d --wait --wait-timeout 120
docker compose -f infra/compose/compose.yml restart gateway
docker compose -f infra/compose/compose.yml up -d --wait --wait-timeout 120
python scripts/smoke-backend.py --verify-persisted-profile
```

Le mode `--verify-persisted-profile` exige aussi de retrouver l’abonnement hashtag du compte technique après redémarrage, puis de retrouver les valeurs de l’exécution précédente avant toute nouvelle modification. Les données sont dans PostgreSQL, pas dans la mémoire du processus Java.

La procédure redémarre ensuite la Gateway pour renouveler ses connexions et sa résolution des services Docker. En local, des adresses de conteneurs peuvent changer lors d’un redémarrage ; des connexions conservées peuvent alors viser un ancien emplacement. Cette procédure de développement implique une interruption et ne garantit pas une reprise sans coupure.

## Prochaine itération

Le socle [media-worker](../services/media-worker/README.md) est disponible : consommation Kafka, base PostgreSQL indépendante, tâches `QUEUED`, déduplication et quarantaine durable. Le prochain chantier est l'exécution de ces tâches avec FFmpeg et les événements de résultat.

Le frontend est connecté à Keycloak, aux profils, au catalogue et aux uploads Video. La chaîne outbox/Kafka et l'ingestion dédupliquée dans le worker sont disponibles ; ajouter ensuite l'exécuteur FFmpeg et les événements de résultat. Les événements User nécessiteront leur propre outbox. Feed et les autres services restent à créer. Aucun service ne doit lire la base d'un autre.

Références : [compatibilité Spring Cloud/Boot](https://spring.io/projects/spring-cloud/), [JWT Spring Security](https://docs.spring.io/spring-security/reference/reactive/oauth2/resource-server/jwt.html), [Keycloak en conteneur](https://www.keycloak.org/server/containers), [hostname Keycloak](https://www.keycloak.org/server/hostname), [support PostgreSQL](https://www.postgresql.org/support/versioning/).
