# Service Skill/Hashtag

`services/skill` est un service Spring Boot indépendant. Il possède sa base PostgreSQL `youscout_skill` et ne lit jamais les bases User ou Keycloak. Les requêtes passent par la Gateway ; le service vérifie lui-même le JWT, son audience et les rôles `user`/`admin`.

## API disponible

Toutes les routes exigent un access token Keycloak valide. Le contrat exécutable est [skill-hashtag.json](../contracts/openapi/skill-hashtag.json).

| Route | Accès | Résultat |
| --- | --- | --- |
| `GET /v1/skills` | user/admin | Catalogue des compétences actives |
| `GET /v1/skills/{id}` | user/admin | Compétence active, sinon 404 |
| `GET /v1/skills/admin` | admin | Catalogue complet, y compris désactivées |
| `GET /v1/skills/admin/{id}` | admin | Compétence avec son état |
| `POST /v1/skills/admin` | admin | Ajout, HTTP 201 |
| `PATCH /v1/skills/admin/{id}` | admin | Nom, description et/ou état actif, HTTP 200 |
| `GET /v1/hashtags` | user/admin | Recherche de hashtags |
| `POST /v1/hashtags` | user/admin | Crée un hashtag (201) ou renvoie l’existant canonique (200) |
| `GET /v1/hashtags/{id}` | user/admin | Hashtag par identifiant |
| `GET /v1/hashtags/following` | user/admin | Abonnements personnels, sélectionnés par le sub du JWT |
| `PUT /v1/hashtags/{id}/follow` | user/admin | Abonnement idempotent, HTTP 204 |
| `DELETE /v1/hashtags/{id}/follow` | user/admin | Désabonnement idempotent, HTTP 204 |

Les listes renvoient `{items, limit, offset}`. `limit` vaut 20 par défaut et reste entre 1 et 100 ; `offset` reste entre 0 et 10000. `q` est une recherche de sous-chaîne littérale normalisée, sans interprétation de `%` ou `_` comme jokers. Le catalogue admin et les deux listes générales acceptent `q` (80 points de code maximum). Les abonnements personnels ne prennent pas `q`.

## Compétences

Exemple d’ajout :

```json
{"name":"Vision du jeu","description":"Lire le placement des partenaires"}
```

Exemple de désactivation :

```json
{"active":false}
```

Le nom est normalisé NFKC, débarrassé des espaces aux extrémités et ses espaces successifs sont réunis. Il doit rester non vide et ne pas dépasser 80 points de code Unicode. Sa clé d’unicité est normalisée et convertie en minuscules avec Locale.ROOT. La description est limitée à 500 points de code ; une chaîne vide l’efface. Null, mauvais types, caractères nuls et champs inconnus sont refusés. Le PATCH conserve les champs absents ; un objet vide est invalide.

Une contrainte unique PostgreSQL empêche les doublons canoniques, même lors d’ajouts concurrents. Un conflit renvoie 409. Une compétence désactivée reste stockée et garde son identifiant ; elle disparaît des lectures utilisateur, mais reste consultable et réactivable par admin. Aucun endpoint de suppression physique n’est fourni.

La migration V1 initialise Dribble, Frappe, Passe, Technique et Vision du jeu. Ce catalogue initial est modifiable via l’API admin ; il ne dépend pas des constantes Flutter.

La migration V2 ajoute `skill_audit`. Chaque ajout ou PATCH admin écrit dans la même transaction l’identifiant de compétence, le sub vérifié de l’acteur, le type d’action et la date. L’API ne propose pas encore de consultation de ce journal ; il ne remplace pas une politique d’audit de production. Aucun rôle ou identifiant transmis par en-tête n’est utilisé comme source d’autorisation.

## Hashtags et abonnements

`{"name":"#FootballMaroc"}` produit `footballmaroc`. La normalisation est NFKC, minuscules Locale.ROOT et retrait d’un éventuel `#` initial. Le nom canonique contient 1 à 50 points de code : lettres, marques Unicode, nombres ou underscore ; les espaces et tirets sont refusés. Les accents et les écritures non latines sont conservés.

La création concurrente d’un même nom conserve un seul identifiant et la date originale. L’abonnement possède une clé unique `(subject, hashtag_id)`. Les PUT/DELETE répétés ne multiplient pas les lignes ; désabonner un hashtag valide non suivi réussit aussi. Un identifiant de hashtag inexistant renvoie 404. Le propriétaire est exclusivement le sub du JWT : ni champ du corps, ni paramètre de requête, ni en-tête ne sélectionne un autre compte.

Les réponses d’erreur utilisent `application/problem+json` : 400 pour données invalides, 401 pour authentification/sujet invalide, 403 pour rôle insuffisant, 404 pour ressource absente/inactive, 409 pour doublon de compétence.

## Lancement et mise à jour

Pour un environnement existant :

```sh
python infra/compose/init-env.py --upgrade
docker compose -f infra/compose/compose.yml up -d --build --wait --wait-timeout 300
python scripts/apply-keycloak.py
python scripts/smoke-backend.py
python scripts/smoke-identity.py
```

`--upgrade` ajoute seulement les secrets manquants, dont `SKILL_DB_PASSWORD`, sans remplacer les anciens. Sur un nouvel environnement, `python infra/compose/init-env.py` génère les cinq secrets. `skill-db` utilise un volume séparé et aucun port de base n’est publié sur l’hôte. Le service écoute sur 8080 dans Docker et 8082 par défaut hors Docker ; seule la Gateway est exposée sur localhost:8080.

```sh
./mvnw -B -ntp verify
python -m unittest discover -s scripts/tests
```

Les tests couvrent migrations, droits, validation, Unicode, désactivation/réactivation, rollback des doublons, concurrence, abonnements et isolation entre comptes. La CI démarre aussi la pile complète : un véritable joueur puis administrateur temporaire teste les API via Keycloak/Gateway. Ses compétences, hashtags et données de test sont nettoyés. Le compte technique du smoke conserve un hashtag d’essai pour vérifier sa persistance après redémarrage de User et Skill.

## Limites et suite

Cette étape livre les API backend. Le raccordement de Flutter au catalogue et l’interface React d’administration restent à faire ; A05 n’est donc pas considéré comme livré de bout en bout. La désactivation/modération des hashtags, les événements Kafka et l’outbox transactionnelle sont des étapes ultérieures. Le journal admin n’est pas un relais outbox. Video devra référencer les identifiants du catalogue via ses contrats, sans accéder à cette base.
