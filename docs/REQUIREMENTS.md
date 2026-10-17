# Exigences et traçabilité

Source : sujet ALD, pages 1-2. Les phases organisent la livraison, sans supprimer les exigences finales. « S » = socle MVP ; « F » = suite fonctionnelle obligatoire ; « A » = administration finale. Les critères ci-dessous sont une précision de conception, pas une preuve d'implémentation.

| ID | Exigence mobile | Responsable | Phase |
| --- | --- | --- | --- |
| U01 | Créer un compte | Auth, User | S |
| U02 | Connexion via Google, Facebook ou autre tiers | Auth | S |
| U03 | Voir et modifier son profil | User | S |
| U04 | Consulter les autres profils | User | S |
| U05 | Publier une vidéo de la galerie | Video, workers | S |
| U06 | Filmer et publier depuis l'application | Flutter, Video | S |
| U07 | Description et hashtags | Video, Skill/Hashtag | S |
| U08 | Sélectionner les compétences démontrées | Video, Skill/Hashtag | S |
| U09 | Supprimer ses vidéos | Video, projections | S |
| U10 | Consulter le feed | Feed | S |
| U11 | Commenter | Interaction | S |
| U12 | Répondre aux commentaires | Interaction | S |
| U13 | Supprimer ses commentaires ou ceux inappropriés sur ses vidéos | Interaction avec contrôle du propriétaire vidéo | S |
| U14 | Liker et annuler un like | Interaction | S |
| U15 | Noter les compétences et modifier son évaluation | Rating | F |
| U16 | Signaler une publication | Moderation | F |
| U17 | Signaler un commentaire | Moderation | F |
| U18 | Bloquer un utilisateur | User, Feed, Chat | F |
| U19 | Suivre un utilisateur ou hashtag | User, Skill/Hashtag | S |
| U20 | Ne plus suivre utilisateur ou hashtag | User, Skill/Hashtag | S |
| U21 | Envoyer et recevoir des messages instantanés | Chat | F |
| U22 | Fermer son compte | User, Auth, orchestration de fermeture | F |

| ID | Exigence back-office | Responsable | Phase |
| --- | --- | --- | --- |
| A01 | Voir tous les utilisateurs | Admin/Analytics, projection User | A |
| A02 | Voir vidéos, commentaires et auteurs | Admin/Analytics, projections Video et Interaction | A |
| A03 | Voir les vidéos signalées et motifs | Moderation, interface admin | F |
| A04 | Désactiver utilisateur, vidéo, commentaire ou hashtag | Moderation et chaque propriétaire | F |
| A05 | Ajouter, désactiver et modifier les skills | Skill/Hashtag, interface admin minimale | S |
| A06 | Dashboard d'informations générales | Admin/Analytics | A |

## Critères d'acceptation de conception

- Identité vérifiée côté serveur ; un utilisateur ne modifie pas le profil d'autrui.
- Publication uniquement après validation/transcodage réussi ; retry sans doublon.
- Suppression vidéo et commentaire selon droits ; la règle U13 vérifie aussi le propriétaire de la vidéo.
- Like et abonnements idempotents ; modifications de note atomiques dans Rating.
- Signalement avec motif, auteur, cible et horodatage ; décision admin auditée.
- Désactivation empêchant nouvelles lectures autorisées, interactions et diffusion dans les projections ; délai maximal des accès média déjà signés explicite.
- Blocage vérifié côté serveur pour chat et visibilité retenue ; fermeture révoquant les nouveaux accès et suivant les traitements de chaque service.
- A01/A02 couvrent tous les enregistrements par pagination et filtrage, pas seulement les éléments signalés.

Notifications push, OpenSearch et diversification du classement sont des extensions de conception. Les règles de notation, de rétention et de classement restent à valider dans [le cadrage](PROJECT.md).
