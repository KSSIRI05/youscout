# État du frontend

Le frontend Flutter propose désormais deux parcours indépendants : une connexion au backend et une démo sociale locale. Voir [les instructions détaillées](../apps/mobile/README.md).

| Disponible avec le compte réel | Encore local ou à développer |
| --- | --- |
| Inscription et connexion Keycloak, Authorization Code + PKCE | Connexion sociale, SMTP |
| Profils publics et abonnements persistants | Validation sur appareils |
| Catalogue des compétences avec recherche et pagination ; administration React (`apps/admin`) | Administration des autres domaines (A01-A04, A06) |
| Hashtags, création et abonnements personnels persistants | Modération des hashtags |
| Compétences et hashtags associés aux vidéos publiées ; notifications (cloche, badge des non lus, boîte, ouverture de la conversation) | Notifications push sur l'appareil (jeton FCM) et console de modération |
| Likes, commentaires, signalements (vidéos et commentaires) ; messagerie instantanée Chat (livraison en direct, états « Envoyé » et « Reçu », reprise après coupure) ; vidéos retirées par la modération signalées dans « Mes vidéos » | Notifications temps réel |
| Renouvellement, erreurs réseau/403/401, déconnexion et fermeture du compte (saisie FERMER) | Stockage de session durable entre relancements |
| Adaptateurs web et AppAuth natif | Builds et parcours Android/iOS sur appareil |
| Sélection, titre, PUT S3 avec progression et confirmation Video, validés sur le web avec le vrai bucket | Validation sur appareils Android/iOS |

Les interactions du mode démo restent en mémoire et ne sont pas mélangées avec le compte réel. En mode connecté, les profils publics et suivis sont persistés par User; le fil peut afficher les vidéos des comptes suivis. Likes, commentaires et signalements sont persistés par Social ; les messages par Chat. L'onglet Vidéo envoie un clip à S3, suit son traitement et permet au propriétaire de lire ou publier un clip `READY`, et de supprimer n'importe lequel de ses clips après confirmation. L'onglet Fil lit les vidéos publiques via une URL S3 temporaire. Les jetons et URL signées ne sont pas conservés sur disque. L'onglet Messages ouvre une connexion WebSocket à Chat lors de son premier affichage : les messages arrivent en direct, le jeton est renvoyé avant son expiration, la connexion se rétablit avec un délai croissant et l'historique est relu depuis la dernière séquence. Sans connexion, l'envoi passe par REST avec le même identifiant client, sans doublon. Les notifications push restent à réaliser.

Les jetons du compte sont conservés en mémoire seulement. Le navigateur garde temporairement l’état PKCE dans sessionStorage jusqu’au retour de Keycloak, puis le supprime. Un rechargement web exige une nouvelle connexion, éventuellement facilitée par la session SSO Keycloak.

Le [test réel du 28 octobre 2026](validation/video-upload-2026-10-28.md) a confirmé l'envoi d'un clip depuis Flutter web jusqu'à l'état PostgreSQL `UPLOADED`, ainsi que le rejeu backend après redémarrage. Aucun jeton ni URL signée n'est conservé dans ce compte rendu.

Validation locale de cette intégration : 29 tests Flutter réussis, analyse statique sans problème et build web release réussi. Un parcours navigateur avec la pile Docker réelle a confirmé l’inscription, le retour PKCE, la modification du profil, le catalogue, la création et le suivi/désabonnement d’un hashtag, puis leur persistance après déconnexion/reconnexion. Un 401 unique injecté dans le navigateur a déclenché un véritable renouvellement Keycloak et le rejeu réussi de la requête. Un callback sans état PKCE a été refusé avant tout appel API. Le compte temporaire, son profil, ses abonnements et son hashtag de test ont été supprimés. Ces essais ne remplacent pas une validation sur appareils Android/iOS.
