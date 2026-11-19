# Analytics

Activité quotidienne du tableau de bord d'administration (A06), port local 8090, PostgreSQL `analytics`. Le service consomme les événements Kafka et ne stocke que des compteurs agrégés par jour UTC : aucun identifiant de compte.

| Mesure | Événement (sujet) |
| --- | --- |
| Nouveaux comptes | `UserProfileCreated` (`user.relationships.v1`), publié par User dans la transaction de création du profil |
| Vidéos publiées | `VideoPublished` (`video.published.v2`) |
| Likes, commentaires | `VideoLiked`, `CommentAdded` (`interaction.events.v1`) |
| Messages | `MessageAccepted` (`chat.messages.v1`) |

Chaque événement est compté une fois (inbox par `eventId`, même transaction que le compteur), dans le jour où il s'est produit. Les autres événements des mêmes sujets sont acquittés sans effet ; un message illisible est mis en quarantaine dans `analytics_rejected_messages`.

**Limite connue :** l'historique commence avec les événements que Kafka retenait au premier démarrage d'Analytics (7 jours). Les jours antérieurs ne sont pas reconstruits ; `coverageStart` indique le premier jour compté et la console l'affiche.

`GET /v1/analytics/activity?days=30` (1 à 90, rôle admin) renvoie une entrée par jour, à zéro sans activité. Contrat : [`analytics.yaml`](../../contracts/openapi/analytics.yaml).

Les totaux du tableau de bord ne viennent pas d'Analytics mais des propriétaires, toujours exacts : `GET /v1/users/admin/accounts/stats`, `/v1/videos/admin/stats`, `/v1/social/admin/stats`, `/v1/ratings/admin/stats`. Les listes A01/A02 sont servies de la même façon par User, Video et Social.

Le service démarre avec le profil Compose `feed`. `python scripts/smoke-admin.py` vérifie listes, totaux et comptage d'un nouveau compte sur la pile complète ; `./mvnw -pl services/analytics test` utilise PostgreSQL et Kafka réels via Testcontainers.
