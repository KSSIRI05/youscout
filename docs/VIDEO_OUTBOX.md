# Outbox Video avec MongoDB et Kafka

## Parcours

`POST /v1/videos/{id}/complete` confirme l'objet S3 et met à jour le document vidéo avec `UPLOADED`. Dans la même transaction MongoDB, Video insère un événement immuable dans `video_outbox`. Si l'insertion échoue, la confirmation est annulée. L'API ne contacte pas Kafka : une panne du broker ne bloque pas les uploads.

La publication suit la même règle : Video passe l'état de publication à `PUBLISHED` et crée `VideoPublished v2` dans une transaction MongoDB. L'outbox et les documents vidéo sont dans la même base.

Le relais réclame un événement prêt dans une courte transaction, en inscrivant un bail `lockedUntil`. Il publie ensuite dans Kafka, puis marque l'événement comme envoyé dans une autre transaction. En cas d'échec, il augmente `attempts`, reporte `nextAttemptAt` avec un délai exponentiel plafonné à 300 secondes et inscrit un code générique dans `lastError`. Chaque lot est limité à dix événements.

Kafka et MongoDB ne partagent pas de transaction. Un crash après l'accusé Kafka mais avant la mise à jour de l'outbox peut republier le même événement ; son `eventId` reste stable. Les consommateurs doivent dédupliquer durablement cet identifiant. Le relais repose sur le replica set MongoDB utilisé par le profil Compose `video`.

## Contrats

Le topic `video.uploaded.v1` transporte `VideoUploaded v1` et utilise `videoId` comme clé Kafka. Le contrat est dans [video-uploaded.v1.schema.json](../contracts/events/video-uploaded.v1.schema.json). `video.published.v2` transporte `VideoPublished v2`, défini dans [video-published.v2.schema.json](../contracts/events/video-published.v2.schema.json). Les événements ne contiennent ni Bearer, ni identifiants AWS, ni URL signée.

## Démarrage local

L'outbox est toujours écrite. Le relais est désactivé par défaut. L'extension Compose Kafka active le relais et initialise le broker ; le service Video dépend du replica set MongoDB.

```powershell
python infra/compose/init-env.py --upgrade
docker compose -f infra/compose/compose.yml -f infra/compose/compose.kafka.yml --profile video up -d --build --wait --wait-timeout 300
python scripts/apply-keycloak.py
```

Dans cet environnement, la configuration locale `VIDEO_MONGODB_IMAGE=mongo:7` utilise l'image déjà présente. Les nouveaux environnements utilisent `mongo:8.0` par défaut. Le service Video ne dépend plus de PostgreSQL pour son fonctionnement normal ; PostgreSQL est isolé dans le profil `video-migration` pour l'import et la reprise.

Les transactions MongoDB portant l'etiquette `TransientTransactionError` sont rejouees depuis le debut, avec un nombre borne de tentatives. La confirmation acquiert d'abord un bail MongoDB par video, puis effectue un seul appel S3 avant sa transaction d'ecriture; les requetes concurrentes recuperent la confirmation existante. Les tests d'integration demarrent MongoDB replica set et Kafka avec Testcontainers. Le smoke `scripts/smoke-outbox.py` injecte ses propres documents MongoDB, verifie la reprise apres panne et les supprime a la fin; le record Kafka synthetique reste soumis a la retention du topic.

Le broker local est un noeud KRaft en PLAINTEXT. Un deploiement distribue doit configurer TLS/SASL, ACL, replicas et supervision.
