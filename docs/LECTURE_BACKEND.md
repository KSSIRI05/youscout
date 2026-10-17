# Lire le backend YouScout

Les commentaires du code expliquent les responsabilités et les règles qui doivent être préservées. Le parcours habituel d’une requête est : client → Gateway → contrôleur du service → service métier → repository JDBC → PostgreSQL.

## Services Java

- **Gateway** valide le JWT et les rôles, applique CORS, borne l’identifiant de corrélation et transmet la requête avec son Bearer. Elle n’accède à aucune base.
- **User** vérifie à nouveau le JWT. `ProfileController` expose `/v1/users/me`, `ProfileService` choisit le propriétaire à partir de `sub` et initialise le profil si nécessaire, et `ProfileRepository` exécute le SQL. `ProfilePatch` distingue les champs absents des valeurs invalides.
- **Skill** vérifie également le JWT. `CatalogController` expose les compétences, hashtags et abonnements. `CatalogInput` valide et normalise les entrées. `CatalogService` définit les transactions ; `CatalogRepository` réalise les écritures et lectures SQL. Les créations et modifications admin sont enregistrées avec leur acteur dans la même transaction.

Les classes `RealmRoles` convertissent uniquement les rôles `user` et `admin` du realm en autorités applicatives. `ApiSecurityErrors` prépare les réponses 401/403. Les tests utilisent des JWT réellement signés et des serveurs JWKS locaux ; User et Skill disposent de bases PostgreSQL Testcontainers temporaires.

## Migrations SQL : explications sans modifier leur empreinte

Les fichiers Flyway déjà appliqués restent inchangés, y compris leurs commentaires : Flyway mémorise leur checksum et refuse une version modifiée. Une évolution du schéma doit passer par une nouvelle migration. Voici l’explication de celles présentes :

| Fichier | Rôle des éléments SQL |
| --- | --- |
| `services/user/src/main/resources/db/migration/V1__create_user_profiles.sql` | `subject` est la clé primaire issue de Keycloak. Les longueurs des noms et biographies correspondent aux limites de validation. Les mots de passe restent dans Keycloak. Les dates enregistrent création et dernière modification. |
| `services/skill/src/main/resources/db/migration/V1__create_catalog.sql` | `skills.name_key` impose l’unicité canonique ; `active` permet la désactivation sans suppression. `hashtags.name` est canonique et unique. La clé primaire `(subject, hashtag_id)` empêche les abonnements multiples ; la clé étrangère exige un hashtag existant. L’index par hashtag facilite les accès dans ce sens. Les cinq compétences initiales ont des UUID fixes. |
| `services/skill/src/main/resources/db/migration/V2__audit_skill_changes.sql` | Chaque entrée du journal possède son UUID, une référence à la compétence, le sujet de l’acteur, une action CREATE/UPDATE et une date. La contrainte CHECK borne les actions et l’index permet de retrouver l’historique d’une compétence. Ce journal ne contient pas les anciennes valeurs et ne constitue pas une outbox événementielle. |

## Configuration JSON et scripts

Le JSON strict n’accepte pas les commentaires. Le realm `infra/keycloak/youscout-realm.json` est expliqué dans [IDENTITE_ET_AUTORISATIONS.md](IDENTITE_ET_AUTORISATIONS.md) : rôles, clients, scopes, PKCE et audience des jetons. Les contrats sous `contracts/openapi` portent leurs explications dans les champs `description` et décrivent le format des requêtes/réponses.

Les fichiers `application.yml` configurent les adresses, l’audience attendue, les bases et les sondes. Les `pom.xml` définissent les modules et dépendances. Le Dockerfile compile le module demandé puis exécute son jar avec un compte non root. Compose relie les services et conserve les données dans des volumes séparés.

`init-env.py` crée les secrets ou ajoute les clés manquantes. `apply-keycloak.py` met à jour la configuration d’identité locale. `keycloak_admin.py` fournit son client d’administration. `smoke-backend.py` vérifie l’infrastructure et la persistance avec un compte technique ; `smoke-identity.py` teste un véritable parcours de formulaires Keycloak et appelle `catalogue_smoke.py` pour vérifier les droits et les opérations du catalogue.
