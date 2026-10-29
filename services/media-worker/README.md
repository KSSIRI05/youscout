# Worker média — ingestion des tâches

Ce module consomme `VideoUploaded` sur `video.uploaded.v1` et enregistre des tâches **`QUEUED`** dans sa propre base PostgreSQL. Il prépare le traitement FFmpeg ; il ne télécharge, ne transforme et ne publie encore aucun média. Aucun accès AWS ni accès à la base Video n'est configuré pour ce socle.

## Traitement d'un message

Le décodeur applique le [contrat VideoUploaded v1](../../contracts/events/video-uploaded.v1.schema.json) : version/producteur attendus, champs exacts, UUID canoniques, clé Kafka égale au `videoId`, type et taille autorisés, clé S3 `videos/{videoId}/source`, ETag opaque et date ISO. Les JSON dépassant 64 Kio, trop imbriqués, contenant des propriétés dupliquées ou des données finales supplémentaires sont refusés.

Le worker persiste le snapshot source et son empreinte sémantique, puis acquitte **l'offset suivant de la seule partition concernée**. L'auto-commit est désactivé. S'il perd la connexion PostgreSQL, la transaction échoue et l'offset n'est pas acquitté. Le consumer est recréé avec le même groupe et reprend les messages non acquittés. En cas de crash après commit PostgreSQL et avant ACK Kafka, la contrainte primaire `event_id` retrouve la tâche existante. Les dates et l'état ne sont pas réécrits lors d'un doublon.

Une contrainte unique `video_id` empêche aussi deux tâches pour la même vidéo. Si un `eventId` est réutilisé avec d'autres données, ou qu'un nouvel événement cible une vidéo déjà enregistrée, la tâche initiale n'est pas modifiée. Le message conflictuel est enregistré dans `rejected_messages`, avec un code générique et ses coordonnées topic/partition/offset, puis acquitté. Un message invalide suit la même procédure pour ne pas bloquer sa partition. La quarantaine doit elle-même être persistée avant l'ACK ; une panne DB n'entraîne jamais son abandon silencieux.

Le worker ne stocke ni payload brut, ni propriétaire, ni jeton, ni URL signée. Le consumer appartient à un seul thread ; son arrêt utilise `wakeup()` puis une fermeture bornée. Les instances partagent le groupe `youscout-media-worker-v1` et se répartissent les partitions. Les contraintes PostgreSQL assurent la déduplication entre instances. La réception ne constitue pas une garantie d'exécution FFmpeg unique : le futur exécuteur devra ajouter leases, reprises et sorties déterministes.

## Base indépendante

La migration V1 crée :

- `processing_jobs` : `event_id` primaire, `video_id` unique, empreinte, état `QUEUED`, bucket/clé/ETag, type, taille, date source et dates de création/modification.
- `rejected_messages` : coordonnées Kafka uniques, code d'erreur et date. Aucun payload n'y est conservé.

Le groupe démarre à `earliest` s'il n'a aucun offset connu : le premier lancement peut donc enregistrer les événements historiques encore retenus, y compris ceux des tests locaux. Une réinitialisation volontaire des offsets ne recrée pas les tâches conservées en base. Les événements expirés dans Kafka ne peuvent pas être reconstruits à partir d'un simple changement d'offset ; une future procédure de réconciliation sera nécessaire. Aucun job ni rejet n'est purgé automatiquement à ce stade.

## Lancement

Depuis la racine, avec Docker démarré :

```powershell
python infra/compose/init-env.py --upgrade
docker compose -f infra/compose/compose.yml -f infra/compose/compose.kafka.yml -f infra/compose/compose.worker.yml --profile video --profile worker up -d --build --wait --wait-timeout 300
python scripts/smoke-worker.py
```

Avec S3 réel pour le producteur Video, préparer sa session AWS puis conserver les **quatre fichiers** à chaque `up` :

```powershell
python scripts/prepare-video-aws.py
docker compose -f infra/compose/compose.yml -f infra/compose/compose.video-aws.yml -f infra/compose/compose.kafka.yml -f infra/compose/compose.worker.yml --profile video --profile worker up -d --build --wait --wait-timeout 300
```

L'override AWS est limité au service Video. Le worker utilise seulement Kafka et `worker-db`. Son mot de passe `MEDIA_WORKER_DB_PASSWORD` est généré localement sans rotation des secrets existants. Le volume `worker-data` conserve les tâches. Aucun port de la base ou du worker n'est publié sur l'hôte ; la santé interne est accessible dans le conteneur sur `/actuator/health/readiness` et reflète PostgreSQL, pas l'accessibilité de Kafka. Il n'existe pas d'API publique de consultation des tâches.

Hors Compose, les paramètres sont `MEDIA_WORKER_DB_URL`, `MEDIA_WORKER_DB_USERNAME`, `MEDIA_WORKER_DB_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS` et éventuellement `MEDIA_WORKER_GROUP_ID`. Le port par défaut est 8084. `MEDIA_WORKER_INGESTION_ENABLED=false` désactive la consommation tout en laissant la base et la santé disponibles. Cette configuration Kafka PLAINTEXT est réservée au développement local ; TLS/SASL et ACL de topic/groupe seront à configurer avant une exposition distribuée.

## Tests

```powershell
.\mvnw.cmd -B -ntp -pl services/media-worker -am verify
python scripts/smoke-worker.py
```

Les tests utilisent Kafka et PostgreSQL Testcontainers. Ils couvrent la validation du contrat, les doublons, les conflits, l'insertion concurrente, le rejeu après arrêt/réinitialisation des offsets, les messages invalides suivis de messages valides, et la reprise après échec d'insertion en base sans avancement des offsets.

Validation locale : **13 tests worker**, **103 tests Java au total** et **3 tests Python** réussis. Le service et sa base ont démarré sains dans Compose ; le smoke a confirmé une seule tâche inchangée après trois livraisons et un redémarrage.

Le smoke publie trois fois le même événement synthétique, dont une fois après redémarrage du worker. Il attend que le groupe par défaut ait rattrapé les trois partitions, vérifie l'unicité de la tâche et ses dates inchangées, puis supprime uniquement cette tâche synthétique. Les événements de test restent dans Kafka jusqu'à expiration de la rétention. Ce test ne fait aucun appel S3 ni aucun transcodage. Il est également inclus dans la CI backend.

Consulter les états sans afficher les données source :

```powershell
docker compose -f infra/compose/compose.yml -f infra/compose/compose.kafka.yml -f infra/compose/compose.worker.yml --profile video --profile worker exec -T worker-db psql -U youscout_media_worker -d youscout_media_worker -c "SELECT event_id, video_id, status, created_at FROM processing_jobs ORDER BY created_at;"
```

## Étape suivante

Ajouter un exécuteur avec états et leases persistants, téléchargement S3 avec `If-Match` sur l'ETag conservé, contrôle `ffprobe`, limites de ressources et FFmpeg. Le résultat sera publié via une outbox propre au worker ; Video consommera `VideoProcessed`/`VideoProcessingFailed` pour faire évoluer son domaine. Aucune écriture directe dans la base Video ne doit être ajoutée.

Référence : [contrôle manuel des offsets Kafka](https://kafka.apache.org/41/javadoc/org/apache/kafka/clients/consumer/KafkaConsumer.html).
