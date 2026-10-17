# Identité et autorisations — état implémenté

Keycloak gère les identifiants et ses propres pages d’inscription/connexion. Les services YouScout ne reçoivent pas le mot de passe des joueurs. Flutter conserve pour le moment son mode démo ; son intégration OIDC et ses callbacks restent à réaliser.

## Parcours joueur

Le realm local `youscout` autorise l’inscription. Un nouveau joueur reçoit le rôle applicatif `user` via le rôle composite par défaut ; `admin` n’est jamais attribué par l’inscription. Le nom utilisateur reste distinct de l’adresse email ; les emails en double sont refusés. Le mot de passe doit contenir au moins 12 caractères, une majuscule, une minuscule et un chiffre. La protection contre les tentatives répétées est activée.

Le client public `youscout-mobile` utilise Authorization Code avec PKCE S256 obligatoire :

1. Le client crée `state`, `nonce`, un `code_verifier` aléatoire et son `code_challenge` SHA-256.
2. Il ouvre le navigateur système vers l’endpoint d’autorisation Keycloak, ou son parcours d’inscription.
3. Keycloak authentifie le joueur et redirige vers un callback exactement autorisé.
4. Le client vérifie `state` et échange le code à usage unique avec son `code_verifier`.
5. Le Bearer reçu permet d’appeler les API. L’application doit gérer le renouvellement et fermer sa session locale lors de la déconnexion.

Callbacks autorisés : `http://localhost:7357/auth/callback`, `http://127.0.0.1:7357/auth/callback` et `ma.youscout://oauth/callback`. Aucun secret client, flux implicite ou password grant n’est activé pour le mobile. Les callbacks ne sont pas encore implémentés dans Flutter : leur configuration dans Keycloak ne crée pas un écran de connexion fonctionnel dans l’application.

L’environnement local n’a pas de serveur SMTP configuré : vérification d’email et récupération de mot de passe par email sont désactivées. Ce sont des limites de la configuration de développement.

## Matrice des droits API

| Endpoint | Sans jeton / jeton invalide | Jeton valide sans rôle applicatif | `user` | `admin` |
| --- | --- | --- | --- | --- |
| `/actuator/health` et probes | 200 si service sain | 200 si service sain | 200 si service sain | 200 si service sain |
| `GET /v1/users/session` | 401 | 200 | 200 | 200 |
| `GET /v1/users/me` | 401 | 403 | Profil personnel | Profil personnel |
| `PATCH /v1/users/me` | 401 | 403 | Profil personnel | Profil personnel |
| `GET /v1/users/admin/session` | 401 | 403 | 403 | 200 |

La route admin est un diagnostic sans écriture ni liste d’utilisateurs. Elle prouve le contrôle du rôle ; le produit d’administration reste à développer. Toute autre route non déclarée dans les règles de sécurité est refusée par défaut.

Gateway et User appliquent chacun cette matrice. Seuls les rôles `user` et `admin` provenant de `realm_access.roles` d’un JWT vérifié deviennent des autorités applicatives. Des en-têtes, des champs du profil, des scopes nommés `admin` ou des rôles client ne confèrent pas ce droit. Un rôle admin ne permet pas de choisir le propriétaire de `/me` : le `sub` reste l’unique sélecteur SQL.

Les clients ont les scopes par défaut `basic`, `profile` et `email`. `basic` fournit notamment le claim `sub` : il est indispensable pour rattacher le profil au joueur. L’outil de mise à jour lie explicitement ces scopes via les endpoints Keycloak dédiés, car le PUT du client seul ne crée pas ces relations. Les clients gardent `fullScopeAllowed=false`. Leur scope de rôles est explicite : `user`/`admin` pour le mobile, uniquement `user` pour le smoke technique. Les protocol mappers exportent l’audience `youscout-api` et les rôles effectifs. Le compte technique possède `user`, sans rôle admin ni permission de gestion Keycloak.

Le rôle applicatif `admin` est distinct des droits d’administration du serveur Keycloak. Son attribution à une personne doit être effectuée par un opérateur autorisé dans Keycloak ; aucune API publique de promotion n’est fournie.

## Sessions et erreurs

Les access tokens durent 5 minutes. Le validateur JWT applique aussi sa tolérance d’horloge par défaut. La session SSO a une inactivité maximale de 30 minutes et une durée maximale de 10 heures. Les refresh tokens tournent à chaque renouvellement et leur réutilisation est refusée.

La déconnexion Keycloak ferme la session et empêche son renouvellement. Un access token déjà émis peut rester accepté jusqu’à expiration avec la tolérance d’horloge : les API vérifient les JWT localement, sans introspection à chaque requête. Une révocation immédiate pour suspension ou action sensible nécessitera le mécanisme prévu dans [SECURITE_ET_MODERATION.md](SECURITE_ET_MODERATION.md).

Les refus de sécurité des API renvoient `application/problem+json` avec `type`, `title`, `status` et `detail` :

- 401 : jeton absent, invalide ou expiré, avec challenge `WWW-Authenticate: Bearer` (et `invalid_token` pour un jeton rejeté).
- 403 : jeton valide mais droits insuffisants.
- Les erreurs de validation des profils conservent HTTP 400 et leur contrat existant.

Les messages n’incluent ni jeton, ni mot de passe, ni stack trace. La Gateway conserve `X-Request-Id` pour corréler le refus.

## Mettre à jour un environnement existant

L’import `--import-realm` initialise un realm neuf ; il ne met pas à jour un realm déjà présent. Depuis la racine, après le démarrage Compose :

```sh
python scripts/apply-keycloak.py
python scripts/smoke-backend.py
python scripts/smoke-identity.py
```

`apply-keycloak.py` applique les paramètres versionnés, le rôle par défaut, les mappers et scopes des deux clients. Il conserve les utilisateurs, les mots de passe, les rôles existants et les secrets clients. Il donne `user` au compte technique et ne promeut aucun joueur administrateur. Les joueurs déjà présents ayant le rôle composite par défaut héritent de `user` ; pour un compte ancien sans ce rôle composite, l’opérateur doit attribuer explicitement `user`.

L’outil utilise uniquement l’administration locale à `localhost:8180`, avec le mot de passe de bootstrap dans le `.env` ignoré. Le client intégré `admin-cli` utilise son flux opérateur pour obtenir ce jeton de gestion en mémoire ; cela n’active pas le password grant du client mobile. Ce n’est pas une procédure d’exploitation de production.

## Diagnostic d’un profil refusé après inscription

Une inscription et un échange de code réussis ne prouvent pas que l’access token contient son sujet. Sans scope par défaut `basic`, un realm neuf peut émettre un jeton avec le rôle user mais sans `sub` ; User refuse alors `/v1/users/me` avec HTTP 401. Un ancien realm ayant conservé ce scope peut masquer le défaut d’import.

La configuration versionnée inclut `basic` pour les deux clients. `apply-keycloak.py` répare les relations sur les realms existants sans supprimer leurs données. Le smoke test vérifie la relation réelle, puis le sujet du jeton avant d’appeler le profil. Il ne substitue jamais le nom utilisateur au sujet du JWT. La CI affiche l’état et les journaux Gateway/User en cas d’échec.

## Vérifications automatisées

```sh
./mvnw -B -ntp verify
python scripts/smoke-identity.py
```

Les tests Java vérifient notamment user/admin, absence de rôle, claims mal formés, fausses promotions par en-tête/scope/rôle client, jetons expirés, erreurs 401/403 et refus par défaut, en plus des profils persistants.

Le test réel Keycloak utilise uniquement la pile locale. Son client HTTP émule les cookies Secure autorisés sur localhost par les navigateurs, sans diminuer les protections du serveur. Il inscrit un compte temporaire via les formulaires publics, vérifie le rôle user, les erreurs de connexion, les contraintes de redirect et PKCE, le code à usage unique, la connexion d’un joueur existant, la rotation des refresh tokens, le refus de leur réutilisation et la déconnexion. Il attribue ensuite admin uniquement à ce compte de test pour vérifier la route réservée. Le compte et son profil de test sont supprimés à la fin. Aucun mot de passe ni jeton n’est écrit dans le dépôt ou affiché.

Validation locale : 31 tests Java réussis, smoke backend réussi, parcours réels Keycloak réussis et nettoyage du compte temporaire effectué. Un import du realm dans un environnement temporaire a aussi confirmé les rôles par défaut, sans promotion admin.

La CI applique la configuration puis exécute ces parcours avec Keycloak réel. Les tests ne prouvent pas encore le fonctionnement du navigateur système sur Android/iOS, de la connexion sociale, du mail ou d’une suspension immédiatement effective.

Références : [cookies Secure sur localhost](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Set-Cookie), [administration Keycloak](https://www.keycloak.org/docs/latest/server_admin/index.html), [OIDC Keycloak](https://www.keycloak.org/securing-apps/oidc-layers), [JWT et autorités Spring Security](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html).
