# Notification

Boîte de notifications de chaque joueur et envoi push (port local 8089, PostgreSQL `notification`). Le service ne reçoit aucune requête des autres services : il consomme leurs événements Kafka.

| Événement (sujet) | Notification |
| --- | --- |
| `UserRelationChanged` FOLLOW (`user.relationships.v1`) | `FOLLOWED` au compte suivi |
| `VideoPublished` (`video.published.v2`) | `VIDEO_PUBLISHED` à chaque abonné du propriétaire |
| `VideoLiked` (`interaction.events.v1`) | `VIDEO_LIKED` au propriétaire de la vidéo |
| `CommentAdded` (`interaction.events.v1`) | `COMMENTED` au propriétaire, `REPLIED` à l'auteur du commentaire parent |
| `MessageAccepted` (`chat.messages.v1`) | `MESSAGE` au destinataire ; une seule notification non lue par conversation, dont `occurrences` augmente |

Règles :

- personne n'est notifié de sa propre action, ni par ou pour un compte désactivé, en fermeture ou fermé, ni à travers un blocage dans un sens ou dans l'autre ;
- un suivi, un like ou une conversation répétés ne créent qu'une notification non lue ; un désabonnement retire la notification de suivi non lue ;
- un blocage efface les notifications entre les deux comptes, une suppression de vidéo efface celles qui la concernent ;
- un événement de plus de 24 h (`NOTIFICATION_MAX_EVENT_AGE`) met à jour les projections sans notifier : ni les suivis rejoués par le rattrapage initial de User, ni l'historique des sujets relu au premier démarrage ne produisent de notifications périmées.

Le service garde sa propre projection des suivis, blocages et comptes inactifs. Chaque événement est appliqué une fois (inbox par `eventId`) dans une transaction, avant le commit de l'offset ; un événement invalide est mis en quarantaine dans `notification_rejected_messages` puis acquitté.

## API (via la Gateway)

`GET /v1/notifications` (pagination `before`, `limit` 1 à 50), `GET /v1/notifications/unread-count`, `POST /v1/notifications/{id}/read`, `POST /v1/notifications/read-all`, `PUT /v1/notifications/devices` (`platform` ANDROID, IOS ou WEB et `token`), `POST /v1/notifications/devices/unregister`. Contrat : [`notification.yaml`](../../contracts/openapi/notification.yaml).

## Push

Chaque notification passe par un envoi push dont le statut est conservé : `SENT`, `NO_DEVICE`, `DISABLED` ou `FAILED`. Le titre est générique (« Nouveau message », « Nouvel abonné »…) avec le texte « Ouvre YouScout pour voir. » : aucun nom ni contenu ne sort de YouScout. Un jeton refusé par FCM comme expiré est oublié.

L'envoi utilise Firebase Cloud Messaging (API HTTP v1), qui couvre Android, le web et iOS une fois APNs configuré dans le projet Firebase. Il reste **désactivé** tant que `FCM_PROJECT_ID` et `FCM_CREDENTIALS_FILE` (fichier JSON du compte de service, monté dans le conteneur) ne sont pas fournis. L'application Flutter n'obtient pas encore de jeton FCM : cela demande le plugin Firebase et les fichiers de configuration du projet.

Les jetons d'appareil sont chiffrés (AES-256-GCM) et retrouvés par une empreinte HMAC ; la clé dérive de `NOTIFICATION_DEVICE_KEY`, généré par `infra/compose/init-env.py`.

## Fermeture de compte

`DELETE /internal/accounts/{subject}` (rôle `account-closure`, appelé par User) efface les notifications reçues et provoquées par le compte, ses appareils et ses relations projetées. Le compte reste marqué inactif pour qu'un événement tardif ne recrée rien.

## Lancer et tester

Le service démarre avec le profil Compose `feed`, comme Feed. `python scripts/smoke-notifications.py` vérifie suivi, messages groupés, appareil, lecture et blocage sur la pile complète. Les tests (`./mvnw -pl services/notification test`) utilisent PostgreSQL et Kafka réels via Testcontainers et une doublure FCM.
