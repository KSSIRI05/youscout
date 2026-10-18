# Socle du service Video

Ce module Spring Boot valide Keycloak, persiste les métadonnées dans PostgreSQL, prépare les uploads S3 et confirme les fichiers reçus. La Gateway expose `GET /v1/videos/session`, `POST /v1/videos/uploads` et `POST /v1/videos/{id}/complete`. La validation du format réel du média, le traitement vidéo et les événements restent à implémenter.

## Identité Keycloak

La Gateway puis Video vérifient indépendamment le Bearer. Video accepte uniquement RS256, vérifie issuer, audience `youscout-api`, dates, présence de l'expiration et `sub` non vide. La tolérance temporelle standard est de 60 secondes. L'issuer est configuré par `OIDC_ISSUER` (défaut `http://localhost:8180/realms/youscout`) et les clés publiques par `OIDC_JWK_SET_URI` (défaut sous cet issuer local). Dans Compose, le téléchargement des clés utilise `http://keycloak:8080`, mais l'issuer attendu reste celui vu par le navigateur.

`GET /v1/videos/session` exige le rôle de realm `user` ou `admin` et retourne uniquement `subject` (le `sub` vérifié) et `service`, avec `Cache-Control: no-store`. La création d'upload exige les mêmes rôles. Aucun identifiant envoyé par Flutter n'est utilisé pour choisir le propriétaire. Les scopes, rôles client et en-têtes d'identité ne remplacent pas les rôles de realm. Les erreurs utilisent `application/problem+json` : 401 pour un jeton absent/invalide, 403 pour un rôle insuffisant. Les autres routes restent refusées par défaut. Voir le [contrat OpenAPI](../../contracts/openapi/video-foundation.yaml).

## Préparer un upload

Envoyer un POST JSON à `/v1/videos/uploads` avec `Authorization: Bearer <jeton>` et `Idempotency-Key` (16 à 128 caractères alphanumériques, tirets ou underscores, par exemple un UUID). Le corps accepte exactement :

```json
{"title":"Mon talent","contentType":"video/mp4","sizeBytes":123456}
```

Le titre est nettoyé et limité à 150 unités UTF-16 ; un caractère NUL provoque une réponse 400 avant insertion en base. Les types acceptés sont `video/mp4`, `video/webm` et `video/quicktime`, avec une taille positive limitée par défaut à 100 Mio. Le propriétaire vient du `sub`. La base conserve UUID, propriétaire, titre, bucket, clé unique `videos/<uuid>/source`, type, taille déclarée, état `PENDING_UPLOAD`, clé d'idempotence et dates. Aucune URL signée ni donnée vidéo n'est persistée.

La réponse 201 contient `id`, `status`, `title`, `sizeBytes`, `createdAt` et `upload` : méthode `PUT`, URL, en-têtes `Content-Type`/`Content-Length` et `expiresAt`. Flutter enverra les octets bruts à cette URL, sans Bearer Keycloak et sans multipart/form-data. Le navigateur calcule `Content-Length` automatiquement : ne pas essayer de le définir manuellement en JavaScript. Type et longueur sont signés et doivent correspondre au fichier.

Une reprise avec le même propriétaire, la même clé d'idempotence et les mêmes données normalisées retourne 200 et la même vidéo avec une nouvelle signature. Un autre corps retourne 409. Une clé identique utilisée par un autre joueur crée sa propre vidéo. Les reprises concurrentes sont protégées par une contrainte unique PostgreSQL. En cas d'échec de signature ou de transaction, aucune création partielle ne subsiste et une erreur 503 générique est renvoyée.

L'URL dure au plus 300 secondes par défaut et cesse aussi de fonctionner à l'expiration des identifiants AWS. Elle n'est pas à usage unique et un PUT peut remplacer le même objet tant qu'elle est valide. Ne jamais la journaliser ni la stocker dans Git. Les dates de signature ne sont pas stockées en base. Le statut reste en attente après un PUT jusqu'à la confirmation explicite. Quotas par joueur, expiration des métadonnées abandonnées, événements et traitement FFmpeg restent à développer.

## Confirmer le fichier reçu

Après le PUT S3, appeler `POST /v1/videos/{id}/complete` avec le Bearer Keycloak, sans corps ou avec `{}`. Aucun propriétaire, bucket, clé ou URL n'est accepté dans le corps. Les rôles `user` et `admin` doivent confirmer uniquement une vidéo dont le propriétaire correspond à leur propre `sub`. Un identifiant inconnu ou appartenant à quelqu'un d'autre renvoie le même 404, sans appel S3.

Le service verrouille la ligne PostgreSQL pendant la vérification, puis appelle `HeadObject` sur le bucket et la clé persistés. Taille et `Content-Type` doivent correspondre aux valeurs attendues. En cas de succès, la migration V2 permet de passer à `UPLOADED` et de conserver `completed_at` et `source_etag`, sans modifier V1. L'appel S3 est borné à cinq secondes au total (trois secondes par tentative). La réponse 200, non cachable, contient `id`, `status`, `title`, `sizeBytes`, `createdAt`, `updatedAt` et `completedAt`.

Un fichier absent/inaccessible ou une taille/un type différent provoque 409 ; une panne S3/SDK, un ETag manquant ou une panne de base provoque 503. L'état reste alors `PENDING_UPLOAD`. Aucun droit IAM supplémentaire n'est ajouté : `HeadObject` utilise `GetObject`. Sans `ListBucket`, AWS peut retourner 403 pour un objet absent ; ce 403 ambigu est traité comme un fichier non confirmable, sans annoncer à tort qu'il existe. Voir [la référence HeadObject](https://docs.aws.amazon.com/AmazonS3/latest/API/API_HeadObject.html).

Une confirmation répétée retourne les mêmes données et dates, sans nouvel appel S3. Des confirmations concurrentes n'appliquent qu'une seule transition. Après confirmation, réutiliser la clé de création d'upload renvoie 409 et ne produit plus de nouvelle URL PUT.

`UPLOADED` signifie que les métadonnées S3 ont été vérifiées à cet instant, pas que le média est lisible, sûr ou prêt à publier. Le type S3 est déclaré par le client ; FFmpeg devra vérifier le format réel et la durée. L'ETag est opaque, pas une preuve MD5 universelle. Les anciennes URL PUT peuvent encore remplacer l'objet avant expiration et le bucket n'est pas versionné : les futurs workers devront lire avec une condition `If-Match` sur `source_etag` et refuser un objet modifié. La confirmation ne révoque pas les URL déjà émises et ne déclenche encore aucun événement Kafka.

## Paramètres

| Variable | Valeur par défaut | Usage |
| --- | --- | --- |
| `VIDEO_S3_BUCKET` | `youscout-014663857971-dev-videos` | Nom du bucket, sans URL ni préfixe `s3://` |
| `VIDEO_S3_REGION` | `us-east-1` | Région passée explicitement au client S3 |
| `VIDEO_AWS_PROFILE` | vide ; `youscout-video-sdk` avec le profil Spring `local` | Nom du profil AWS, jamais un secret |
| `SERVER_PORT` | `8083` ; `8080` dans Compose | Port HTTP du processus |
| `VIDEO_DB_URL` | `jdbc:postgresql://localhost:5434/youscout_video` | URL JDBC ; remplacée par le réseau privé dans Compose |
| `VIDEO_DB_USERNAME` | `youscout_video` | Compte propre à Video |
| `VIDEO_DB_PASSWORD` | aucune | Secret généré localement par `init-env.py --upgrade` |
| `VIDEO_MAX_UPLOAD_BYTES` | `104857600` | Taille maximale déclarée (1 octet à 5 Gio) |
| `VIDEO_UPLOAD_URL_SECONDS` | `300` | Validité de signature (30 à 900 secondes) |

Les propriétés `youscout.storage.s3` sont validées au démarrage. Le projet utilise des noms de bucket sans point (3 à 63 caractères, minuscules, chiffres et tirets). Les valeurs fournies doivent correspondre aux ressources réelles et aux permissions IAM : changer la configuration ne crée pas un autre bucket et n'étend pas les droits du rôle.

Les identifiants sont résolus au premier appel AWS, pas au démarrage. Le profil local sélectionne explicitement `YouScoutVideo` via le profil de compatibilité préparé dans [la configuration IAM](../../infra/iam/VIDEO.md). Sans profil explicite, le SDK utilise sa chaîne standard, notamment l'identité de workload en cloud. Ne pas utiliser le profil administrateur ou Terraform pour ce service. Ne pas définir de clés AWS dans les YAML, le code, les arguments de lancement ou Flutter.

## Démarrer sur le poste Windows

Pour un lancement Java natif, fournir aussi une base PostgreSQL accessible, `VIDEO_DB_URL` et `VIDEO_DB_PASSWORD` dans l'environnement du processus (ne pas écrire de mot de passe dans la commande ou l'historique). La base Compose n'expose aucun port hôte. Avec Java 21 et une session `youscout-login` valide :

```powershell
# Contrôle le rôle et la connexion ; n'affiche aucun identifiant temporaire.
aws sts get-caller-identity --profile youscout-video-sdk
$env:VIDEO_S3_BUCKET = 'youscout-014663857971-dev-videos'
$env:VIDEO_S3_REGION = 'us-east-1'
.\mvnw.cmd -pl services/video spring-boot:run '-Dspring-boot.run.profiles=local'
```

La santé est accessible à `http://localhost:8083/actuator/health/readiness`. Le profil `local` ne lit pas automatiquement `infra/compose/.env` : utiliser les variables du terminal pour modifier ses valeurs. Le profil AWS reste dans le dossier personnel du développeur, hors du dépôt.

## Vérifier dans Compose

Depuis la racine, ajouter le mot de passe Video sans modifier les secrets existants :

```powershell
python infra/compose/init-env.py --upgrade
docker compose -f infra/compose/compose.yml --profile video config --quiet
docker compose -f infra/compose/compose.yml --profile video up -d --build --wait --wait-timeout 300 video
docker compose -f infra/compose/compose.yml ps video
```

Le profil Compose `video` est distinct du profil Spring `local`. Il démarre `video-db` et son volume persistant `video-data`, sans port hôte. La readiness inclut PostgreSQL ; elle n'appelle pas S3. Sans identité AWS, la santé fonctionne mais une demande valide de signature retourne 503. La configuration du poste Windows n'est pas montée dans l'image.

Pour signer réellement depuis Docker en développement local :

```powershell
python scripts/prepare-video-aws.py
docker compose -f infra/compose/compose.yml -f infra/compose/compose.video-aws.yml --profile video up -d --build --wait --wait-timeout 300 video gateway
```

Le préparateur refuse tout autre rôle que `YouScoutVideo`. Les seuls identifiants transmis sont temporaires, conservés sous `infra/compose/.aws/`, avec accès local restreint, exclus de Git et du contexte Docker. Le dossier est monté en lecture seule, et le SDK Linux lit le JSON par `credential_process` ; AWS CLI et les profils administrateurs restent sur le poste. Relancer le préparateur avant expiration : le fichier est remplacé sans redémarrer le service. Les signatures déjà émises restent limitées par leur session initiale. Pour un hôte Linux, le compte non privilégié du conteneur doit pouvoir lire ce dossier privé ; adapter le propriétaire sans ouvrir les permissions à tous. En production, utiliser une identité de workload Kubernetes : cette passerelle est locale uniquement.

Pour vérifier le parcours Keycloak réel après construction de Video et de la Gateway :

```powershell
docker compose -f infra/compose/compose.yml -f infra/compose/compose.video-aws.yml --profile video up -d --build --wait --wait-timeout 300 video gateway
python scripts/apply-keycloak.py
python scripts/smoke-video.py
```

Cette commande conserve la passerelle préparée à l'étape précédente. Après son activation, inclure les deux fichiers Compose dans toute commande `up` visant Video ; utiliser uniquement le fichier de base recréerait le service sans son montage d'identifiants et empêcherait la signature S3.

Le smoke utilise le compte technique existant `youscout-smoke`, jamais un mot de passe joueur. Il ne journalise aucun jeton et ne crée ni vidéo ni profil. Il vérifie le refus anonyme, le `sub` renvoyé par Video malgré les en-têtes falsifiés, le routage, l'absence de cache et le rejet d'une demande d'upload invalide.

Avec la passerelle AWS locale active, `python scripts/smoke-video-upload.py --put-object --complete --verify-restart` teste un ticket, un très petit PUT S3 réel, la confirmation, le redémarrage Video et la reprise idempotente de la confirmation. Sans `--complete`, il teste seulement la préparation et sa reprise en attente. Il supprime uniquement son objet temporaire avec le rôle de provisionnement (Video ne peut pas supprimer), puis sa ligne de test. Il ne valide pas le format vidéo du contenu. Aucune URL ni aucun jeton n'est affiché. Le test réel S3 est volontairement absent de la CI sans accès AWS.

## Vérification Java

```powershell
.\mvnw.cmd -B -ntp -pl services/video -am verify
```

Les tests chargent les vrais YAML, vérifient les remplacements de bucket/région et le profil dédié, rejettent une URL à la place du nom du bucket et une région vide, puis démarrent le serveur pour vérifier la santé et le refus des autres routes. Ils n'effectuent aucun appel AWS et n'exigent aucun identifiant. Le workflow backend les inclut par le module Maven parent.

Les 30 tests Video et les 15 tests Gateway passent le 18 octobre 2026. Ils couvrent les vrais JWT, PostgreSQL Testcontainers et un vrai presigner avec identifiants factices, sans réseau AWS : création, champs signés, isolation des propriétaires, titres NUL, validation, reprises concurrentes et rollback en cas d'échec de signature. Les tests de confirmation simulent les réponses S3 pour vérifier réussite, absence, refus d'accès, taille/type incorrects, panne SDK/S3, ETag manquant et reprise stable. Les trois tests Python de génération des secrets restent inclus dans le workflow CI. La CI démarre Video et sa base mais ne reçoit aucun identifiant AWS réel.

Le smoke local avec `--put-object --verify-restart` a également réussi : PUT S3 réel, persistance après redémarrage et nettoyage des seules données temporaires. Les identifiants et URL sont restés hors des logs et du dépôt. Cela ne valide ni le format réel du média ni son traitement.

Références : [chaîne d'identifiants AWS SDK Java](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials-chain.html), [sélection de région](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/region-selection.html).
