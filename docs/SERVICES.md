# Services et propriété des données

Ces services constituent la cible. La Gateway et les profils personnels persistants du service User sont implémentés dans le [socle backend](BACKEND.md), avec Keycloak local ; le [service Skill/Hashtag](SKILL_HASHTAG.md) possède également ses API et sa base dédiée. Les autres fonctions métier du tableau restent à livrer. Une infrastructure partagée ne donne jamais un droit d'écriture croisée. La Gateway ne contient pas de logique de notation ou de modération.

| Service | Responsabilité et exemples d'API | Données propriétaires | Événements représentatifs |
| --- | --- | --- | --- |
| Auth (Keycloak) | OIDC, compte tiers, sessions et rôles | PostgreSQL `auth` | Lifecycle via adaptateur, pas de mots de passe dans Kafka |
| User | `/v1/users`, profils, follow utilisateur, block, fermeture | PostgreSQL `user` | UserUpdated, UserFollowed, UserBlocked, AccountClosureRequested |
| Video | `/v1/videos`, upload, description, tags, état et suppression | MongoDB `video`, objets S3 via droits contrôlés | VideoUploaded, VideoPublished, VideoDeleted |
| Interaction | `/v1/videos/{id}/comments`, réponses, likes et annulation | MongoDB `interaction`, Redis dédié aux compteurs | CommentCreated, CommentDeleted, LikeChanged |
| Rating | `/v1/ratings/videos/{id}`, création/modification/retrait de notes par compétence | PostgreSQL `rating` | RatingChanged (à venir) |
| Feed | `/v1/feed`, vue de lecture classée et paginée | Redis `feed`, projections reconstruisibles | Consomme publications, relations, retraits et interactions |
| Chat | `/v1/conversations`, historique et envoi de secours ; WebSocket `/v1/chat/ws` via la Gateway ([détails](../services/chat/README.md)) | MongoDB `chat`, Redis Pub/Sub entre instances | MessageAccepted, MessageDelivered (à venir) |
| Skill/Hashtag | `/v1/skills`, `/v1/hashtags`, catalogue et abonnements hashtags | PostgreSQL `skill` | SkillChanged, HashtagFollowed, HashtagDisabled |
| Moderation | `/v1/reports`, décisions admin, suivi d'application | PostgreSQL `moderation`, journal de décisions | ModerationActionRequested, ModerationActionApplied |
| Notification | `/v1/notifications`, boîte, non lus, appareils, envoi push ([service](../services/notification/README.md)) | PostgreSQL `notification`, jetons chiffrés/accès restreint | Consomme UserRelationChanged, UserAccountChanged, VideoPublished, VideoDeleted, VideoLiked, CommentAdded, MessageAccepted ; conserve statut de livraison |
| Admin/Analytics | `/v1/analytics/activity` ([service](../services/analytics/README.md)) ; listes et totaux servis par les propriétaires (`/v1/users/admin/accounts`, `/v1/videos/admin`, `/v1/social/admin/comments`, routes `stats`) | PostgreSQL `analytics`, compteurs agrégés par jour | Consomme UserProfileCreated, VideoPublished, VideoLiked, CommentAdded, MessageAccepted |

Le tableau décrit la cible. Les contrats des profils et du catalogue sont dans `contracts/openapi` ; les autres API et événements restent à formaliser. Les rôles ne suffisent pas : contrôler aussi propriétaire de la ressource, conversation et blocages.

## Modèles et invariants

- `User` : identifiant stable relié au `sub` OIDC ; le profil n'est pas le compte d'authentification.
- `Video` : auteur, description, références de skills/hashtags, clés objets, état, version. Les tags référencent le catalogue, sans accès direct à sa base.
- `Comment` : auteur, vidéo, parent optionnel, état. Indexer l'accès par vidéo et parent.
- `Like` : unicité `(userId, videoId)` ; un retry ne multiplie pas les likes.
- `Rating` : unicité `(rater_subject, video_id, skill_id)`, échelle entière de 1 à 5, pas de note sur sa propre vidéo ni à travers un blocage ([détails](../services/rating/README.md)).
- `Follow` utilisateur et `Block` appartiennent à User ; les abonnements hashtags appartiennent à Skill/Hashtag.
- `Message` : conversation, émetteur, identifiant client de déduplication, numéro de séquence de conversation et dates d'acceptation/livraison.
- `Report` : cible, type, motif, auteur du signalement, date et statut. Le motif n'est pas exposé publiquement.

## Recherche et administration

En phase 3, un indexeur technique reçoit les événements des propriétaires et alimente des index OpenSearch distincts (profils, hashtags, vidéos). Les résultats sont filtrés par visibilité. L'index n'est jamais la source de vérité. L'admin consulte les projections, mais ses commandes sont envoyées au service propriétaire ou à Moderation. Les compteurs du dashboard peuvent être retardés ; les décisions d'accès utilisent un état d'autorisation récent.

## Écritures fiables

PostgreSQL : mutation métier et outbox dans la même transaction. MongoDB : utiliser un replica set compatible avec les transactions pour écrire document et outbox atomiquement, ou un schéma d'outbox embarqué réellement atomique ; ne pas faire deux écritures indépendantes. Les caches Redis de chaque domaine utilisent des droits séparés, pas seulement un préfixe supposé protecteur.
