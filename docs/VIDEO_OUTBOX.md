# VideoUploaded : PostgreSQL outbox et Kafka

## Parcours implémenté

Après le PUT S3, `POST /v1/videos/{id}/complete` vérifie le propriétaire et le fichier comme auparavant. La même transaction PostgreSQL confirme la vidéo et insère un événement immuable dans `outbox_events`. Si l'insertion échoue, le statut reste `PENDING_UPLOAD`. Les confirmations répétées ou concurrentes ne créent qu'un événement. L'API ne contacte pas Kafka et reste utilisable pendant une panne du broker.

Le relais lit les lignes non publiées avec `FOR UPDATE SKIP LOCKED`, une transaction par événement. Il attend l'accusé Kafka avant de renseigner `published_at`. Une autre instance saute la ligne verrouillée. Chaque passage traite au plus dix messages ; une panne interrompt le lot. L'attente réseau est bornée : métadonnées deux secondes, livraison quatre secondes et attente du résultat cinq secondes. Le verrou et une connexion JDBC sont détenus pendant l'envoi, sur le thread du relais, distinct des requêtes HTTP.

En cas d'erreur, `attempts` augmente et `next_attempt_at` est reporté de 1, 2, 4… secondes, jusqu'à 300 secondes. `last_error` contient seulement un code générique. Les reprises ne sont jamais abandonnées automatiquement ; les événements publiés ne sont pas purgés. Alertes de backlog, rétention de l'outbox et gestion opérateur des erreurs permanentes restent à ajouter avant la production.

## Contrat et garantie

Le topic est **`video.uploaded.v1`**, avec **`videoId` comme clé Kafka**. Le [schéma JSON versionné](../contracts/events/video-uploaded.v1.schema.json) définit `eventId`, `eventType`, `eventVersion`, `occurredAt`, `producer` et les références du fichier dans `data` : `videoId`, `s3Bucket`, `s3Key`, `sourceETag`, `contentType`, `sizeBytes`. Aucun titre personnel, propriétaire, Bearer, identifiant AWS ou URL signée n'est diffusé.

La livraison est **au moins une fois**. L'idempotence du producteur ne rend pas atomiques Kafka et PostgreSQL : un crash après l'ACK et avant le commit peut republier le message. Le même `eventId` et le même payload sont réutilisés. Les futurs consommateurs doivent dédupliquer cet identifiant **durablement**, avec l'enregistrement de l'effet métier dans leur propre transaction. Aucun worker de traitement n'est encore branché sur ce topic.

La migration **V3** ajoute l'outbox et un événement pour chaque vidéo déjà `UPLOADED`. Elle ne modifie pas V1/V2 et ne confirme aucune vidéo en attente. Les événements historiques sont publiés lorsque le relais est activé. La contrainte unique `(aggregate_id, event_type)` empêche plusieurs `VideoUploaded` pour une même vidéo.

## Démarrage local

L'enregistrement en outbox est toujours actif. Le relais est désactivé par défaut pour permettre l'utilisation de Video sans broker. L'extension Compose l'active et crée explicitement le topic : trois partitions, une réplique, sept jours de rétention. Le volume `kafka-data` conserve les messages et les métadonnées KRaft entre redémarrages. Le port hôte est limité à `127.0.0.1:9092` ; les conteneurs utilisent `kafka:9092`.

Depuis la racine avec Docker démarré :

```powershell
python infra/compose/init-env.py --upgrade
docker compose -f infra/compose/compose.yml -f infra/compose/compose.kafka.yml --profile video up -d --build --wait --wait-timeout 300
python scripts/apply-keycloak.py
python scripts/smoke-outbox.py
```

Pour conserver aussi S3 réel, préparer une session AWS Video valide et utiliser les **trois fichiers** à chaque `up` :

```powershell
python scripts/prepare-video-aws.py
docker compose -f infra/compose/compose.yml -f infra/compose/compose.video-aws.yml -f infra/compose/compose.kafka.yml --profile video up -d --build --wait --wait-timeout 300
```

Ne pas omettre une extension lors d'un démarrage ultérieur de Video : Compose pourrait recréer le conteneur sans Kafka ou le montage AWS. Le script de smoke utilise seulement `restart video`, qui conserve la configuration existante.

Hors Compose : fournir `VIDEO_OUTBOX_ENABLED=true`, `KAFKA_BOOTSTRAP_SERVERS=localhost:9092`, la base et un topic déjà créé. La readiness de Video dépend de sa base, pas de Kafka. La dépendance Compose à `kafka-init` garantit seulement le provisionnement initial du topic ; une panne ultérieure du broker n'arrête pas Video.

Le broker KRaft unique en PLAINTEXT est destiné au poste local. Un déploiement distribué nécessitera TLS/SASL, ACL, répliques et supervision. L'image est épinglée à `apache/kafka:4.1.2`, comme le client géré par le BOM Spring Boot actuel. Références : [image officielle](https://hub.docker.com/r/apache/kafka), [configuration officielle 4.1.2](https://github.com/apache/kafka/tree/4.1.2/docker).

## Vérifications

```powershell
.\mvnw.cmd -B -ntp verify
python scripts/smoke-outbox.py
```

Les tests Java utilisent PostgreSQL et Kafka Testcontainers, sans AWS. Ils vérifient la confirmation atomique, ses reprises concurrentes, le backfill V3, le contrat du message, l'ACK, une vraie panne du broker, les relais concurrents et un rejeu après ACK ambigu.

Validation locale du **28 octobre 2026** : **90 tests Java** et **3 tests Python** réussis ; extension Compose démarrée avec topic et volume persistants. Le smoke du relais a réussi, y compris panne réelle du broker et redémarrage de Video. La migration V3 a également été appliquée à la base locale existante et l'événement historique a été publié.

Avec une session AWS valide et les trois fichiers Compose actifs, le test suivant a confirmé la chaîne S3 réelle jusqu'à un événement acquitté, ainsi que les rejeux après redémarrage :

```powershell
python scripts/smoke-video-upload.py --put-object --complete --verify-restart --verify-outbox
```

Ce test utilise un petit objet de transport synthétique, pas un média à traiter. Il supprime son objet S3 et ses lignes PostgreSQL avec les identités dédiées. Les messages Kafka de test restent soumis à la rétention. Les URL signées et jetons ne sont jamais affichés. La CI a été étendue avec ces tests sans AWS, mais sa nouvelle exécution distante nécessite un push.

Le smoke de la pile déployée suspend temporairement Kafka, insère des métadonnées synthétiques, attend une erreur enregistrée, redémarre Video, réactive Kafka et vérifie le message reçu. Il teste le câblage du relais ; il ne simule pas un upload S3 ni une confirmation HTTP. Il supprime uniquement ses lignes PostgreSQL. Le message synthétique Kafka expire selon la rétention. Exécuter ce test uniquement sur la pile locale : il suspend temporairement toute publication sur ce broker. La CI exécute également ce test, sans login AWS.

Examiner le backlog sans afficher les payloads :

```powershell
docker compose -f infra/compose/compose.yml --profile video exec -T video-db psql -U youscout_video -d youscout_video -c "SELECT id, aggregate_id, attempts, next_attempt_at, last_error FROM outbox_events WHERE published_at IS NULL ORDER BY created_at;"
```

## Étape suivante

Créer le worker FFmpeg et sa déduplication persistante, puis `VideoProcessed`. Le worker devra lire S3 avec **`If-Match: sourceETag`** : une URL PUT encore valide peut remplacer la source après confirmation. Il publiera ses résultats sans modifier directement la base Video. Les transitions de traitement/publication seront ajoutées à Video avec leurs migrations. Pour l'instant, les vidéos restent `UPLOADED` après publication Kafka.
