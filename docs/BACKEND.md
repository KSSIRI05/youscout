# Socle backend — développement local

Cette itération ajoute Java 21, Spring Boot 4.0.8, Spring Cloud 2025.1.3, Keycloak 26.7.3 et PostgreSQL 17.11. Les versions Maven sont fixées, ainsi que les tags des images. Les tags Docker ne sont pas verrouillés par digest.

## Composants réellement implémentés

- `services/gateway` : Spring Cloud Gateway WebFlux, route `/v1/users/**`, contrôles JWT, CORS local et identifiant `X-Request-Id` borné et propagé. Aucun appel aux bases.
- `services/user` : Spring MVC, validation indépendante des JWT, connexion JDBC à sa propre base et migration Flyway `user_profiles`.
- `infra/compose` : deux processus PostgreSQL avec utilisateurs et volumes distincts, Keycloak et les deux applications Java.
- `infra/keycloak` : realm `youscout`, rôles `user`/`admin`, client public `youscout-mobile` avec Authorization Code + PKCE S256 obligatoire et audience `youscout-api`.
- Contrat de diagnostic dans `contracts/openapi/user-foundation.yaml`.

`GET /v1/users/session` renvoie seulement l’identité issue d’un JWT vérifié. Ce n’est pas `GET /users/me` : aucun profil n’est créé ou modifié. La table de profils est prête pour l’itération suivante. La démo Flutter reste locale et n’utilise pas encore ce backend.

## Lancer avec Docker Compose

Depuis la racine, avec Docker Engine/Docker Desktop démarré, Compose v2 et Python 3 :

```sh
python infra/compose/init-env.py
docker compose -f infra/compose/compose.yml config --quiet
docker compose -f infra/compose/compose.yml up -d --build --wait --wait-timeout 300
python scripts/smoke-backend.py
```

Le générateur crée `infra/compose/.env` ignoré par Git, avec des mots de passe aléatoires. Il refuse d’écraser un fichier existant. Ne pas le relancer à chaque démarrage. Compose charge ce fichier depuis le dossier du fichier Compose. Les valeurs de `.env.example` sont des indications, pas des secrets utilisables.

| Accès local | Adresse |
| --- | --- |
| Gateway et santé | http://localhost:8080/actuator/health |
| Keycloak, console admin | http://localhost:8180/admin/ |
| Découverte OIDC | http://localhost:8180/realms/youscout/.well-known/openid-configuration |

L’administrateur Keycloak s’appelle `admin` ; son mot de passe est dans le fichier `.env` local. Aucun compte joueur ni mot de passe de démonstration n’est committé. Les ports publiés écoutent uniquement sur `127.0.0.1`. Le service User et les bases ne publient aucun port sur l’hôte.

```sh
docker compose -f infra/compose/compose.yml ps
docker compose -f infra/compose/compose.yml logs --tail 100 gateway user
docker compose -f infra/compose/compose.yml down
```

`down` arrête les conteneurs et conserve les volumes. Les mots de passe des bases s’appliquent à leur première initialisation : changer `.env` ne change pas les mots de passe de volumes existants. L’import du realm se fait à sa création ; les modifications du JSON ne réécrivent pas automatiquement un realm déjà importé. Planifier une migration explicite des données/configurations existantes.

## Identité et réseau

L’issuer reste `http://localhost:8180/realms/youscout`, adresse vue par le navigateur. Dans Docker, les applications obtiennent les clés via `http://keycloak:8080/.../certs`, mais continuent de vérifier cet issuer externe. Elles vérifient la signature RS256, les dates du jeton, l’issuer et l’audience `youscout-api` ; un jeton valide pour une autre API est refusé. La Gateway transmet le Bearer et User le valide de nouveau. Les en-têtes de rôle/identité fournis par le client ne sont pas une source d’autorisation.

Le client mobile autorise exactement les callbacks `http://localhost:7357/auth/callback`, `http://127.0.0.1:7357/auth/callback` et `ma.youscout://oauth/callback`. Flutter doit encore implémenter ces callbacks et le stockage de session. Aucun secret client mobile, flux implicite ou password grant n’est activé.

Le client confidentiel `youscout-smoke` est réservé à la vérification locale : client credentials, sans connexion interactive, secret aléatoire passé à Keycloak par environnement. Le script récupère un vrai jeton et teste Gateway → User, sans afficher jeton ni secret. Cela ne prouve pas une connexion de joueur. Ce client et le mode `start-dev` ne constituent pas une configuration de production.

Les routes métier exigent un jeton ; seule la santé est publique, sans détails internes. CORS permet les deux origines locales sur le port 7357, sans cookies. L’admin métier et les règles de propriété ne sont pas encore implémentés. Le RBAC métier sera ajouté avec leurs endpoints. La readiness de User inclut la disponibilité de sa base.

## Compiler et tester

JDK 21 et Docker sont nécessaires pour l’ensemble des tests. Le wrapper télécharge Maven 3.9.11.

```sh
./mvnw -B -ntp verify
```

Sous PowerShell :

```powershell
.\mvnw.cmd -B -ntp verify
```

Les tests démarrent de vrais serveurs HTTP, vérifient des JWT signés avec des clés RSA de test, puis contrôlent les refus pour mauvaise signature, issuer, audience et expiration. Gateway couvre le routage, la propagation du jeton et du request-id, et CORS. User utilise un PostgreSQL éphémère Testcontainers et vérifie la migration. Les conteneurs de test sont nettoyés automatiquement. Ils n’utilisent pas les bases persistantes Compose.

Validation locale effectuée : 12 tests d’intégration réussis via le wrapper Maven, images Docker construites, cinq conteneurs Compose sains et smoke test réussi avec un jeton Keycloak réel. Aucun compte mobile ni profil métier persistant n’a été testé.

La CI `backend.yml` exécute les tests, construit les images, démarre Compose et lance le smoke test Keycloak réel. Aucun déploiement cloud n’est configuré.

## Prochaine itération

Implémenter le profil persistant (`GET /v1/users/me`, `PATCH /v1/users/me`) et ses validations/contrôles de propriété, puis brancher OIDC/PKCE et ces API dans Flutter. Les événements User nécessiteront une outbox et Kafka avant d’alimenter d’autres services. Video, Feed et les autres services restent à créer. Aucun service ne doit lire la base d’un autre.

Références : [compatibilité Spring Cloud/Boot](https://spring.io/projects/spring-cloud/), [JWT Spring Security](https://docs.spring.io/spring-security/reference/reactive/oauth2/resource-server/jwt.html), [Keycloak en conteneur](https://www.keycloak.org/server/containers), [hostname Keycloak](https://www.keycloak.org/server/hostname), [support PostgreSQL](https://www.postgresql.org/support/versioning/).
