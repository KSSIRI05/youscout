# Console d'administration YouScout

Console web React + TypeScript (ADR-011) pour l'exigence **A05** : ajouter, modifier, désactiver et réactiver les compétences du catalogue. Elle n'utilise que les endpoints `/v1/skills/admin` de la Gateway, qui exigent le rôle `admin` ; le service Skill vérifie lui-même le jeton et le rôle.

## Connexion

- Client Keycloak public `youscout-admin`, Authorization Code + PKCE S256, sans mot de passe direct ni flux implicite. Redirection autorisée : `http://localhost:7358/callback`.
- Les jetons restent en mémoire, jamais dans `localStorage` : recharger la page demande une nouvelle connexion. Seuls le verifier et le state de la tentative passent par `sessionStorage` pendant la redirection, avec une validité de 10 minutes.
- Le jeton est renouvelé peu avant son expiration (rotation du refresh token). « Se déconnecter » ferme la session Keycloak.
- Un compte sans le rôle `admin` voit « Accès refusé ». Ce contrôle côté navigateur n'est qu'un confort d'affichage : la sécurité repose sur la Gateway et Skill.

## Démarrer en local

Prérequis : Node.js 22 et la pile Compose démarrée (Keycloak sur `:8180`, Gateway sur `:8080`). Après mise à jour du dépôt, `python scripts/apply-keycloak.py` crée le client `youscout-admin` dans un realm existant.

```powershell
cd apps/admin
npm ci
npm run dev   # http://localhost:7358
```

Le port 7358 est fixe : il doit correspondre aux redirections Keycloak et au CORS de la Gateway. `VITE_OIDC_ISSUER` et `VITE_API_URL` remplacent les adresses locales par défaut.

Le rôle `admin` s'attribue dans la console Keycloak (realm `youscout`, utilisateur, *Role mapping*), puis il faut se reconnecter.

## Vérifier

```powershell
npm run typecheck
npm test        # Vitest + Testing Library : PKCE (vecteur RFC 7636), callback, renouvellement, écrans
npm run build
```

La CI `.github/workflows/admin.yml` exécute ces commandes à chaque modification de `apps/admin`.
