# Etat de l'implementation par rapport a l'architecture cible

Date de reference : 2 novembre 2026.

L'architecture cible reste celle de [ARCHITECTURE.md](ARCHITECTURE.md). Ce tableau distingue les services et flux deja utilisables des choix de persistance qui n'ont pas encore ete portes dans le code. Les ecarts sont des jalons ouverts, pas des changements silencieux de la cible.

| Domaine cible | Implementation actuelle | Reste pour la cible |
| --- | --- | --- |
| Auth, Gateway, User | Keycloak, JWT, Gateway, profils, suivis, blocages persistants et outbox Kafka de relations ; blocage applique par Feed et Social | Durcissement multi-replicas de l'ordre de relais ; fermeture de compte (U22) livrée : journal par étape, effacement par chaque service propriétaire, suppression Keycloak |
| Skill/Hashtag | Catalogue, recherche, suivi de hashtags | Outbox/evenements catalogue et interface admin |
| Video et media-worker | MongoDB en replica set pour metadonnees, etats et outbox (tests d'integration sur replica set Testcontainers) ; upload S3 prive, traitement FFmpeg, publication, suppression par le proprietaire avec `VideoDeleted`, retrait du fil par Feed, nettoyage S3 par le worker et bouton de suppression dans Flutter | HLS, CDN prive, reconciliation |
| Feed | Projection VideoPublished, projection Redis des suivis/blocages, cache de pages versionne | Fan-out des publications par utilisateur, retrait de contenus, reconstruction automatisee de Redis |
| Chat | Implemente (U21) : service dedie, MongoDB (sequence par conversation, deduplication par identifiant client), WebSocket via la Gateway, relais Redis entre instances, blocages verifies aupres de User | Evenement MessageDelivered, etat « lu » ; MessageAccepted publie pour Notification |
| Interaction, Moderation | Social agrege likes, commentaires avec reponses et signalements ; ecritures verifiees aupres de Video (publication) et User (blocages), suppression par le proprietaire de la video (U13), pagination par curseur ; moderation : signalement des commentaires, file admin, retrait de commentaires et de videos (VideoModerated, masquage dans Feed) avec decisions journalisees | Frontieres separees, migration MongoDB |
| Rating | Implemente (U15) | Evenement RatingChanged et prise en compte dans le classement du fil |
| Notification | Implemente : service dedie (PostgreSQL), consommation Kafka de User, Video, Social (outbox VideoLiked/CommentAdded) et Chat (outbox MessageAccepted), boite et badge dans Flutter, appareils chiffres, envoi FCM pret mais desactive sans identifiants | Projet Firebase et jeton FCM dans Flutter, preferences de notification |
| Admin/Analytics | Implemente : listes d'administration paginees chez chaque proprietaire (comptes User, videos Video, commentaires Social, noms des auteurs), totaux exacts par service, service Analytics (PostgreSQL) comptant l'activite quotidienne depuis Kafka (UserProfileCreated, VideoPublished, VideoLiked, CommentAdded, MessageAccepted), tableau de bord dans la console | Historique anterieur au premier demarrage non reconstruit (retention Kafka 7 jours), autres mesures a definir |
| Exploitation | Compose local, CI, S3 Terraform, Kafka local | Kubernetes/staging, OTel/metrics/logs/traces, sauvegardes, restauration et charge |

## Decisions de persistance

La cible conserve PostgreSQL pour User, Skill, Rating, Moderation, Notification et Analytics ; MongoDB pour Video, Interaction et Chat ; Redis pour les projections Feed et compteurs Interaction ; S3 pour les binaires ; OpenSearch pour la recherche derivee.

Le code utilise PostgreSQL pour User, Skill, Feed, Social et le worker. Video utilise MongoDB en replica set ; PostgreSQL reste disponible pour l'import/sauvegarde. Redis porte les relations projetees et un cache de pages Feed versionne, tandis que la projection durable des publications reste dans PostgreSQL. Social n'a pas encore migre vers MongoDB.

## Fil personnalise

Feed projette les publications et les relations via Kafka. User persiste chaque changement avec son outbox transactionnel ; un backfill termine par un marqueur avant que Feed serve des pages. Redis conserve les ensembles de relations et met en cache les pages par versions de relation/publication. Une modification invalide les pages logiquement en incrementant les versions ; les anciennes cles expirent. La projection est eventuelle et doit etre reconstruite si son volume Redis est perdu. La lecture video reste autorisee par Video, independamment de cette projection.
