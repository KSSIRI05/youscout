# Socle du service Video

`POST /v1/videos/{id}/publish` permet au propriétaire de publier une vidéo `READY`. L'état de publication est indépendant de l'upload et du traitement. Une publication écrit `VideoPublished v2` dans l'outbox transactionnelle ; le relais envoie l'événement catalogue vers `video.published.v2`. Les IDs sont validés par Skill et les noms sont enregistrés comme instantanés. Voir [le contrat et les garanties de publication](../../docs/VIDEO_PUBLICATION.md).

Ce module Spring Boot valide Keycloak et persiste les documents Video et l'outbox transactionnelle dans MongoDB. PostgreSQL reste réservé à l'import ponctuel et à la conservation de la sauvegarde source. Le service prépare les uploads S3, confirme les fichiers reçus et publie les événements dans Kafka. La Gateway expose `GET /v1/videos/session`, `POST /v1/videos/uploads` et `POST /v1/videos/{id}/complete`. Le profil de traitement du worker valide le média et publie son résultat ; voir [le guide FFmpeg](../../docs/MEDIA_PROCESSING.md) et [le guide Kafka/outbox](../../docs/VIDEO_OUTBOX.md).

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

Une reprise avec le meme proprietaire, la meme cle d'idempotence et les memes donnees normalisees retourne 200 et la meme video avec une nouvelle signature. Un autre corps retourne 409. Une cle identique utilisee par un autre joueur cree sa propre video. Les reprises concurrentes sont protegees par un index unique MongoDB sur proprietaire et cle d'idempotence. En cas d'echec de signature ou de transaction, aucune creation partielle ne subsiste et une erreur 503 generique est renvoyee.

L'URL dure au plus 300 secondes par défaut et cesse aussi de fonctionner à l'expiration des identifiants AWS. Elle n'est pas à usage unique et un PUT peut remplacer le même objet tant qu'elle est valide. Ne jamais la journaliser ni la stocker dans Git. Les dates de signature ne sont pas stockées en base. Le statut reste en attente après un PUT jusqu'à la confirmation explicite. Quotas par joueur, expiration des métadonnées abandonnées, événements et traitement FFmpeg restent à développer.

## Confirmer le fichier reçu

Après le PUT S3, appeler `POST /v1/videos/{id}/complete` avec le Bearer Keycloak, sans corps ou avec `{}`. Aucun propriétaire, bucket, clé ou URL n'est accepté dans le corps. Les rôles `user` et `admin` doivent confirmer uniquement une vidéo dont le propriétaire correspond à leur propre `sub`. Un identifiant inconnu ou appartenant à quelqu'un d'autre renvoie le même 404, sans appel S3.

Le service acquiert un bail MongoDB par video avant d'appeler `HeadObject` sur le bucket et la cle persistes. Les confirmations concurrentes attendent cette operation au lieu de relancer S3. Taille et `Content-Type` doivent correspondre aux valeurs attendues. Une transaction MongoDB passe ensuite le document a `UPLOADED`, conserve `completedAt` et `sourceETag`, puis ecrit l'evenement dans l'outbox. L'appel S3 est borne a cinq secondes au total (trois secondes par tentative). La reponse 200, non cachable, contient `id`, `status`, `title`, `sizeBytes`, `createdAt`, `updatedAt` et `completedAt`.

Un fichier absent/inaccessible ou une taille/un type différent provoque 409 ; une panne S3/SDK, un ETag manquant ou une panne de base provoque 503. L'état reste alors `PENDING_UPLOAD`. Aucun droit IAM supplémentaire n'est ajouté : `HeadObject` utilise `GetObject`. Sans `ListBucket`, AWS peut retourner 403 pour un objet absent ; ce 403 ambigu est traité comme un fichier non confirmable, sans annoncer à tort qu'il existe. Voir [la référence HeadObject](https://docs.aws.amazon.com/AmazonS3/latest/API/API_HeadObject.html).

Une confirmation répétée retourne les mêmes données et dates, sans nouvel appel S3. Des confirmations concurrentes n'appliquent qu'une seule transition. Après confirmation, réutiliser la clé de création d'upload renvoie 409 et ne produit plus de nouvelle URL PUT.

`UPLOADED` signifie que les métadonnées S3 ont été vérifiées à cet instant, pas que le média est lisible, sûr ou prêt à publier. Le type S3 est déclaré par le client ; FFmpeg devra vérifier le format réel et la durée. L'ETag est opaque, pas une preuve MD5 universelle. Les anciennes URL PUT peuvent encore remplacer l'objet avant expiration et le bucket n'est pas versionné : les futurs workers devront lire avec une condition `If-Match` sur `source_etag` et refuser un objet modifié. La confirmation ne révoque pas les URL déjà émises. Elle écrit `VideoUploaded` dans l'outbox dans la même transaction ; le relais activé le publie ensuite dans Kafka, avec livraison au moins une fois et `eventId` stable.

## Paramètres

Le profil de traitement ajoute `GET /v1/videos/{id}` pour consulter ses propres états de réception et de traitement. Le résultat reste privé, sans clé S3 ni URL de lecture. Video consomme `VideoProcessed`/`VideoProcessingFailed` avec un inbox transactionnel, contrôles de source et d'ETag, puis passe `processingStatus` à `READY` ou `FAILED`. L'état d'upload et les dates de `/complete` restent stables. Voir [le guide FFmpeg](../../docs/MEDIA_PROCESSING.md) pour l'activation et les limites.

| Variable | Valeur par défaut | Usage |
| --- | --- | --- |
| `VIDEO_S3_BUCKET` | `youscout-014663857971-dev-videos` | Nom du bucket, sans URL ni préfixe `s3://` |
| `VIDEO_S3_REGION` | `us-east-1` | Région passée explicitement au client S3 |
| `VIDEO_AWS_PROFILE` | vide ; `youscout-video-sdk` avec le profil Spring `local` | Nom du profil AWS, jamais un secret |
| `SERVER_PORT` | `8083` ; `8080` dans Compose | Port HTTP du processus |
| `VIDEO_MONGODB_URI` | `mongodb://localhost:27017/youscout_video?replicaSet=rs0` | Connexion au replica set Video |
| `VIDEO_MONGODB_IMAGE` | `mongo:8.0` | Image locale ; cette machine utilise `mongo:7` depuis son `.env` ignoré |
| `VIDEO_DB_URL` | `jdbc:postgresql://localhost:5434/youscout_video` | Connexion source pour l'import ponctuel PostgreSQL, pas utilisée par les endpoints |
| `VIDEO_DB_USERNAME` | `youscout_video` | Compte de la base source pour l'import |
| `VIDEO_DB_PASSWORD` | aucune | Secret local requis uniquement pour démarrer `video-db` en profil `video-migration` |
| `VIDEO_MAX_UPLOAD_BYTES` | `104857600` | Taille maximale déclarée (1 octet à 5 Gio) |
| `VIDEO_UPLOAD_URL_SECONDS` | `300` | Validité de signature (30 à 900 secondes) |

Les propriétés `youscout.storage.s3` sont validées au démarrage. Le projet utilise des noms de bucket sans point (3 à 63 caractères, minuscules, chiffres et tirets). Les valeurs fournies doivent correspondre aux ressources réelles et aux permissions IAM : changer la configuration ne crée pas un autre bucket et n'étend pas les droits du rôle.

Les identifiants sont résolus au premier appel AWS, pas au démarrage. Le profil local sélectionne explicitement `YouScoutVideo` via le profil de compatibilité préparé dans [la configuration IAM](../../infra/iam/VIDEO.md). Sans profil explicite, le SDK utilise sa chaîne standard, notamment l'identité de workload en cloud. Ne pas utiliser le profil administrateur ou Terraform pour ce service. Ne pas définir de clés AWS dans les YAML, le code, les arguments de lancement ou Flutter.

## Démarrer sur le poste Windows

Pour un lancement Java natif, fournir un replica set MongoDB via `VIDEO_MONGODB_URI`. PostgreSQL est requis uniquement pour l'import ponctuel ; la base Compose n'expose aucun port hôte. Avec Java 21 et une session `youscout-login` valide :

```powershell
# Contrôle le rôle et la connexion ; n'affiche aucun identifiant temporaire.
aws sts get-caller-identity --profile youscout-video-sdk
$env:VIDEO_MONGODB_URI = 'mongodb://localhost:27017/youscout_video?replicaSet=rs0'
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

Le profil Compose `video` démarre MongoDB en replica set et le service Video ; PostgreSQL n'est pas requis pour le fonctionnement normal. La readiness inclut MongoDB ; elle n'appelle pas S3. Sans identité AWS, la santé fonctionne mais une demande valide de signature retourne 503. La configuration du poste Windows n'est pas montée dans l'image.

Pour signer réellement depuis Docker en développement local :

```powershell
python scripts/prepare-video-aws.py
docker compose -f infra/compose/compose.yml -f infra/compose/compose.video-aws.yml --profile video up -d --build --wait --wait-timeout 300 video gateway
```

Si Kafka est activé, conserver aussi `-f infra/compose/compose.kafka.yml` dans cette commande et les suivantes. Voir [le guide outbox](../../docs/VIDEO_OUTBOX.md) pour le lancement avec les trois fichiers et le test de reprise.

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

Les tests d'integration utilisent MongoDB Testcontainers configure en replica set et Kafka Testcontainers. Ils couvrent l'upload, les transactions, la deduplication, les resultats du worker, la publication, la lecture privee et les roles Keycloak. La commande `verify` ci-dessus execute ces tests.

Le smoke local avec `--put-object --verify-restart` a également réussi : PUT S3 réel, persistance après redémarrage et nettoyage des seules données temporaires. Les identifiants et URL sont restés hors des logs et du dépôt. Cela ne valide ni le format réel du média ni son traitement.

Références : [chaîne d'identifiants AWS SDK Java](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials-chain.html), [sélection de région](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/region-selection.html).

## Import PostgreSQL vers MongoDB

MongoDB est le stockage actif du service Video. Le profil Compose `video` utilise un replica set local pour les transactions multi-documents. Dans l'environnement de développement actuel, le snapshot initial a été importé et les comptes MongoDB/PostgreSQL ont correspondu.

L'importeur ponctuel copie un snapshot PostgreSQL `REPEATABLE READ` vers `videos`, `video_outbox`, `processing_result_inbox` et `rejected_processing_results`. Les compétences et hashtags sont intégrés aux documents vidéo. Les UUID deviennent des chaînes, les horodatages des dates BSON et les payloads outbox restent des objets JSON. Le marqueur `video_migration_runs/postgres-video-v1` conserve les comptes importés ; il empêche de répéter cet import initial. Aucun secret ni URL S3 temporaire n'est importé.

Pour reproduire un import initial, arrêter Video, le worker et les autres producteurs, sauvegarder PostgreSQL, puis démarrer les deux bases :

```powershell
docker compose -f infra/compose/compose.yml --profile video-migration up -d video-db
docker compose -f infra/compose/compose.yml --profile video up -d video-mongo video-mongo-init
docker compose -f infra/compose/compose.yml --profile video --profile video-migration run --rm --no-deps `
  -e VIDEO_POSTGRES_TO_MONGO_ENABLED=true `
  video --spring.main.web-application-type=none
```

L'import initial a été exécuté dans cet environnement. Il n'est pas une réplication continue. Ne pas remettre en service une ancienne version de Video qui écrit vers PostgreSQL ; toute reprise de PostgreSQL exige une procédure de rattrapage explicite. Le volume PostgreSQL et sa sauvegarde sont conservés pour le retour arrière.
