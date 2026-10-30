# Exécution des tâches média

Le profil de traitement complète la chaîne : `VideoUploaded` → tâche worker → lecture S3 conditionnelle → `ffprobe` → FFmpeg → MP4/miniature dans S3 → outbox du résultat → Kafka → état de traitement dans Video.

## États, leases et reprises

La migration worker **V2** conserve les tâches existantes et ajoute `PROCESSING`, `SUCCEEDED` et `FAILED`. Un claim PostgreSQL utilise `FOR UPDATE SKIP LOCKED`, attribue un `attempt_id` aléatoire, augmente `attempts` et pose un lease de dix minutes. Aucun appel S3 ou FFmpeg n'est effectué sous un verrou de base.

Le worker exécute une tâche à la fois par instance. Les délais cumulés des commandes et appels S3 sont bornés pour rester inférieurs au lease. Une tâche abandonnée peut être reprise après expiration, avec un nouveau token. Seule une tentative encore propriétaire de son token et d'un lease valide peut finaliser la tâche et créer l'événement de résultat. Une tentative expirée ne peut pas remplacer le résultat de sa remplaçante.

Les erreurs transitoires, notamment réseau, stockage ou timeout, sont retentées jusqu'à trois tentatives avec un délai de 10 puis 20 secondes. Les erreurs de média et le changement de source sont définitifs. Une dernière tentative abandonnée est récupérée pour émettre `RETRY_EXHAUSTED`, sans relancer FFmpeg indéfiniment. Aucun endpoint de relance manuelle d'une tâche terminale n'est encore fourni.

## Lecture et validation de la source

Le client accepte uniquement le bucket configuré et la clé `videos/{videoId}/source`. Le GET S3 utilise exactement **`If-Match: sourceETag`**. Un 412 devient `SOURCE_CHANGED` ; la source remplacée n'est pas traitée. Taille annoncée et octets lus doivent correspondre à la tâche. La limite de téléchargement est de 100 Mio.

`ffprobe` vérifie une piste vidéo, une durée positive de 180 secondes maximum, des dimensions positives et un plafond de 3840 par axe / 8 294 400 pixels. Les conteneurs autorisés sont MOV/MP4 et Matroska/WebM. Une piste audio seule ou une image de couverture n'est pas considérée comme une vidéo.

Les outils sont lancés avec `ProcessBuilder`, sans shell ni arguments fournis directement par l'utilisateur. Les protocoles FFmpeg sont limités à `file,pipe` et les formats d'entrée sont limités. Les diagnostics des outils ne sont jamais diffusés aux utilisateurs ou dans Kafka. Les variables AWS et mots de passe ne sont pas transmis à l'environnement des sous-processus. Le profil Docker applique un utilisateur non privilégié, un système de fichiers racine en lecture seule, un tmpfs de 512 Mio, deux CPU, 1 Gio de mémoire et 128 processus au maximum. Ces limites constituent un socle local ; elles ne remplacent pas un isolement renforcé de décodeurs en production.

## Fichiers produits

FFmpeg génère un MP4 H.264/AAC, pixels `yuv420p`, 30 images/s et au plus 1280×720, avec métadonnées retirées et `faststart`. Une miniature JPEG est extraite de la version de lecture. Le fichier produit est à nouveau inspecté ; une différence de durée excessive est refusée. Le transcodage est limité à 120 secondes par défaut, le probe à dix secondes et la miniature à vingt secondes.

Chaque tentative écrit sous son propre préfixe :

```text
processed/{videoId}/{attemptId}/video.mp4
processed/{videoId}/{attemptId}/thumbnail.jpg
```

Les PUT utilisent `video/mp4` et `image/jpeg`. Les objets restent privés. Seuls les fichiers d'une tentative finalisée sont référencés par le résultat. Des fichiers partiels ou issus d'un lease perdu peuvent rester sans référence : leur réconciliation et leur suppression S3 restent à automatiser. Aucun droit de suppression n'est ajouté au worker. Les dossiers temporaires sont supprimés après chaque tentative ; le tmpfs Docker disparaît à l'arrêt du conteneur.

## Résultats et cohérence de Video

La transition terminale et l'entrée `worker_outbox` sont écrites dans une même transaction. Le relais attend l'ACK Kafka avant `published_at`, retente après panne et conserve le même `eventId`. Le topic **`video.processing.results.v1`**, clé `videoId`, transporte `VideoProcessed` ou `VideoProcessingFailed` selon le [contrat v1](../contracts/events/video-processing-result.v1.schema.json). Le résultat comprend `sourceEventId`, `attemptId`, `sourceETag` et les références des sorties ou un code d'erreur contrôlé, sans URL signée ni identifiant AWS.

Video consomme avec le groupe `youscout-video-results-v1`. Sa migration **V4** ajoute un inbox durable et les métadonnées de traitement. Le service vérifie l'événement source de sa propre outbox, l'ETag, le bucket et les préfixes exacts des sorties. Il applique le résultat et enregistre l'inbox dans une même transaction, puis acquitte Kafka. Les doublons ne réécrivent pas les dates ; les événements invalides, inconnus, conflictuels ou tardifs sont conservés en quarantaine sans payload. Aucun worker ne lit ou ne modifie la base Video.

Les deux états sont séparés pour préserver le contrat de confirmation :

| Domaine | États |
| --- | --- |
| Réception de la vidéo | `PENDING_UPLOAD` → `UPLOADED` |
| Traitement dans Video | `WAITING` → `READY` ou `FAILED` |
| Tâche du worker | `QUEUED` → `PROCESSING` → `SUCCEEDED` ou `FAILED` |

`POST /complete` conserve ses dates et son résultat après traitement. **`GET /v1/videos/{id}`** permet au propriétaire Keycloak de consulter les métadonnées et le code d'échec, avec `Cache-Control: no-store`. Un administrateur ne peut pas consulter la vidéo d'un autre joueur. Les clés S3 et URL de lecture ne sont pas exposées par cet endpoint. La lecture privée dans Flutter et la publication dans le fil restent les prochaines intégrations.

## Activer le traitement local avec S3

Le traitement reste désactivé par défaut ; l'ingestion peut continuer à remplir la file sans accès AWS. Depuis la racine, Docker démarré et avec une session AWS du projet valide :

```powershell
python infra/compose/init-env.py --upgrade
python scripts/prepare-video-aws.py
docker compose -f infra/compose/compose.yml -f infra/compose/compose.kafka.yml -f infra/compose/compose.worker.yml -f infra/compose/compose.processing.yml -f infra/compose/compose.video-aws.yml -f infra/compose/compose.processing-aws.yml --profile video --profile worker up -d --build --wait --wait-timeout 300
python scripts/apply-keycloak.py
python scripts/smoke-processing.py
```

Conserver les six fichiers lors des `up` suivants. `compose.processing.yml` construit l'image avec FFmpeg et crée le topic de résultats. Les deux extensions AWS montent le dossier privé `.aws` en lecture seule dans Video et le worker. Le rôle temporaire local existant du projet permet déjà GetObject/PutObject dans ce bucket ; aucun droit IAM supplémentaire n'est créé. Une identité de workload peut être utilisée hors développement en laissant le profil AWS vide. En production, utiliser un rôle propre au worker limité à la lecture des sources et à l'écriture des fichiers dérivés.

Les paramètres de `worker.processing` sont dans `application.yml`. `MEDIA_PROCESSING_ENABLED`, `MEDIA_WORKER_AWS_PROFILE`, `VIDEO_S3_BUCKET`, `VIDEO_S3_REGION`, `FFMPEG_BIN` et `FFPROBE_BIN` contrôlent l'activation et l'environnement. Les propriétés valident des plafonds pour éviter qu'une configuration dépasse la durée du lease. Renouveler le fichier d'identifiants temporaires avant expiration pour les longs essais. Le profil d'ingestion seul n'installe pas FFmpeg et ne consomme pas de crédits S3 pour le traitement.

## Vérifications

```powershell
.\mvnw.cmd -B -ntp verify
python -m unittest discover -s scripts/tests
```

Validation locale : **127 tests Java et 7 tests Python** réussis. Les nouveaux tests exercent FFmpeg réellement, PostgreSQL et Kafka Testcontainers, et le SDK S3 contre un serveur HTTP isolé. Ils couvrent le changement d'ETag, les limites du média, les délais, le fencing, les reprises, l'atomicité des deux outbox/inbox, la panne Kafka et la confidentialité des métadonnées. Aucun identifiant AWS n'est nécessaire à ces tests. Le Dockerfile de traitement a également été construit avec FFmpeg.

Le smoke S3 réel crée un petit clip synthétique, vérifie `READY` via la Gateway et une tâche `SUCCEEDED`, puis vérifie le rejeu de `/complete`. Il supprime uniquement sa source, ses fichiers dérivés et ses lignes de base ; ses messages Kafka restent soumis à la rétention. Ce test demande une session AWS Video et Terraform valide, et n'est pas exécuté par la CI sans identifiants. La CI installe FFmpeg pour les tests isolés, tout en gardant le traitement S3 réel désactivé dans le smoke d'ingestion.

Références : [protocoles FFmpeg](https://ffmpeg.org/ffmpeg-protocols.html), [S3 GetObject et If-Match](https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObject.html).
