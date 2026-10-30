# YouScout — Frontend Flutter

Interface en français avec deux modes indépendants : compte connecté au backend et démonstration locale. Le mode connecté utilise les API de profil, de compétences, de hashtags et d'upload vidéo ; le fil social, les interactions et les messages restent dans la démo.

## Exécuter sur le web

Démarrer Docker puis, depuis la racine du dépôt :

```sh
python infra/compose/init-env.py --upgrade
docker compose -f infra/compose/compose.yml up -d --build --wait --wait-timeout 300
python scripts/apply-keycloak.py
```

Depuis `apps/mobile`, avec Flutter 3.47.7 / Dart 3.13.5 :

```sh
flutter pub get
flutter run -d web-server --web-hostname localhost --web-port 7357
```

Ouvrir **http://localhost:7357**, puis « Créer mon compte » ou « Se connecter ». L’inscription et la connexion ont lieu dans Keycloak ; Flutter ne recueille aucun mot de passe. Le retour utilise exactement `/auth/callback`. L’hôte 127.0.0.1 est aussi autorisé ; ne pas changer d’hôte entre le départ et le retour.

Pour servir le build release depuis la racine :

```sh
cd apps/mobile
flutter build web --release --no-web-resources-cdn
cd ../..
python scripts/serve-mobile.py
```

Le serveur local fournit `index.html` sur `/auth/callback`, sans journaliser le code OIDC. Un autre hébergeur web doit fournir la même réécriture SPA. Il faut servir l’application à la racine du domaine, avec les URL de redirection et les origines CORS correspondantes configurées côté Keycloak/Gateway.

## Parcours connecté

- Inscription et connexion Authorization Code + PKCE S256 ; contrôle de state et validité de dix minutes de la demande web.
- Consultation de `/v1/users/me`, puis modification du nom (1–80 caractères) et de la biographie (500 maximum). Les données affichées sont confirmées par le serveur.
- Catalogue des compétences actives, recherche et pages suivantes.
- Recherche des hashtags, création canonique et abonnement/désabonnement par identifiant. Les abonnements personnels sont rechargés par pages.
- Chargement, erreur réseau, refus 403 et session expirée sont visibles. Un 401 provoque un seul renouvellement et une seule nouvelle tentative ; un échec d’authentification confirmé ferme la session.
- Déconnexion locale immédiate et déconnexion Keycloak ; si le serveur est inaccessible, l’application indique que la fermeture distante n’est pas confirmée.

Les jetons restent **en mémoire**, sans localStorage, sessionStorage ni fichier. Le sessionStorage web contient seulement le verifier/state PKCE pendant la redirection, retirés avant l’échange du code. Le callback est immédiatement nettoyé de l’historique. Un rechargement de page demande de se reconnecter ; la session navigateur Keycloak peut éviter de ressaisir les identifiants. Fermer un onglet ne révoque pas à lui seul la session Keycloak : utiliser « Se déconnecter ».

## Envoyer une vidéo depuis le compte connecté

Activer Video et son accès AWS temporaire depuis la racine du dépôt, après connexion AWS du développeur :

```sh
python infra/compose/init-env.py --upgrade
python scripts/prepare-video-aws.py
docker compose -f infra/compose/compose.yml -f infra/compose/compose.video-aws.yml --profile video up -d --build --wait --wait-timeout 300 video gateway
```

Conserver les **deux fichiers Compose** pour les démarrages suivants afin de conserver le montage AWS. Voir [le guide Video](../../services/video/README.md) pour les profils et le renouvellement des identifiants ; aucune clé AWS n'est placée dans Flutter.

Se connecter à YouScout, ouvrir **Vidéo**, choisir un fichier MP4, WebM ou MOV non vide de 100 Mio maximum, saisir un titre de 1 à 150 unités UTF-16 puis envoyer. Le client crée une demande avec `POST /v1/videos/uploads` et une clé d'idempotence aléatoire, envoie les octets bruts avec un PUT HTTPS vers S3 sans Bearer Keycloak, puis confirme avec `POST /v1/videos/{id}/complete`. Le navigateur calcule Content-Length ; le client natif le fixe à la taille du fichier. Les URL signées restent uniquement en mémoire et ne sont jamais affichées dans les erreurs.

La progression distingue préparation, envoi et confirmation. Après une erreur, le bouton réessaie avec la même clé et le même titre ; une nouvelle préparation renouvelle le lien PUT. Si le PUT a réussi, seule la confirmation est répétée. Une sélection d'un autre fichier commence une nouvelle demande. Changer d'onglet conserve la tentative ; recharger l'application ou se déconnecter la perd. Un PUT interrompu n'est pas repris par morceaux : le fichier est renvoyé en entier. Le web charge au plus la taille autorisée en mémoire ; le natif utilise un flux.

Le message « Vidéo reçue et enregistrée » apparaît après confirmation `UPLOADED`. Mes vidéos affiche ensuite l'état du traitement et permet de lire les clips prêts, avec miniature, lecture/pause et renouvellement automatique des liens privés S3. Activer les six fichiers Compose décrits dans [le guide du traitement](../../docs/MEDIA_PROCESSING.md) pour exécuter FFmpeg et Kafka. Les compétences/hashtags associés et l'intégration au fil restent à développer. Voir [le guide de lecture privée](../../docs/PRIVATE_PLAYBACK.md).

Validation automatisée : 41 tests Flutter réussis, analyse statique sans problème et build web release réussi. Les nouveaux tests couvrent le contrôleur, l'interface, le renouvellement Keycloak simulé et le transfert natif vers un serveur HTTP de test ; ils ne nécessitent aucun identifiant AWS. Le 28 octobre 2026, le parcours complet Flutter web → Gateway → S3 réel a également été validé dans Edge avec un clip MP4 synthétique de deux secondes : message de réception dans l'interface, état `UPLOADED`, taille et confirmation persistés en PostgreSQL. Le test backend réel a confirmé le rejeu idempotent après redémarrage. Voir [le compte rendu](../../docs/validation/video-upload-2026-10-28.md). Les parcours Android/iOS restent à valider sur appareils.

## Android et iOS

Les adaptateurs natifs utilisent [flutter_appauth](https://pub.dev/packages/flutter_appauth), le navigateur système et le retour `ma.youscout://oauth/callback`. Les manifestes Android/iOS sont configurés pour ce schéma, déjà autorisé par le realm.

Pour un émulateur Android et le backend local, rendre les deux ports accessibles au navigateur système et à Flutter :

```sh
adb reverse tcp:8080 tcp:8080
adb reverse tcp:8180 tcp:8180
flutter run --dart-define=ALLOW_INSECURE_OIDC=true
```

L’exception HTTP Android est limitée à la configuration debug. Pour iOS et les versions distribuées, utiliser des endpoints HTTPS accessibles depuis l’appareil, enregistrés dans Keycloak et dans la configuration CORS si nécessaire. Les builds et parcours Android/iOS sur appareil restent à valider ; la configuration native n’est pas présentée comme une validation matérielle.

## Configuration

```sh
flutter run -d web-server --web-port 7357 --dart-define=API_URL=https://api.example.org --dart-define=OIDC_ISSUER=https://identity.example.org/realms/youscout
```

Le client public est `youscout-mobile` : aucun secret client n’est distribué. Les adresses HTTP autres que localhost sont refusées. `ALLOW_INSECURE_OIDC` sert seulement au debug natif local ; utiliser HTTPS hors développement.

## Démonstration locale

« Explorer la démo » conserve les talents fictifs, le fil, les likes, commentaires, notes, blocages, messages et prévisualisations vidéo. Ces données restent en mémoire, ne sont pas celles du compte connecté et ne sont pas envoyées aux services. Aucun upload, transcodage, chat distant ou signalement à un modérateur n’est encore réalisé.

## Vérifications et organisation

```sh
dart format --output=none --set-exit-if-changed lib test
flutter analyze
flutter test
flutter build web --release --no-web-resources-cdn
```

`lib/backend` contient les clients HTTP, l’authentification par plateforme, PKCE et l’état du compte connecté. `lib/screens/connected_screen.dart` affiche les profils et le catalogue réels. Le store et les écrans historiques restent dédiés à la démo. Les tests couvrent l’état de session, les refus d’accès, la rotation, les modifications confirmées, la pagination, PKCE et les interactions d’interface ; leurs API simulées complètent les essais avec la pile locale réelle.

Les illustrations Figma et la licence de police sont décrites dans [assets/README.md](assets/README.md).

Validation locale de cette intégration : 29 tests Flutter réussis, analyse statique sans problème et build web release réussi. Un parcours navigateur avec la pile Docker réelle a confirmé l’inscription, le retour PKCE, la modification du profil, le catalogue, la création et le suivi/désabonnement d’un hashtag, puis leur persistance après déconnexion/reconnexion. Un 401 unique injecté dans le navigateur a déclenché un véritable renouvellement Keycloak et le rejeu réussi de la requête. Un callback sans état PKCE a été refusé avant tout appel API. Le compte temporaire, son profil, ses abonnements et son hashtag de test ont été supprimés. Ces essais ne remplacent pas une validation sur appareils Android/iOS.
