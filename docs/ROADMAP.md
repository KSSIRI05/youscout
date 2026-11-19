# Roadmap de réalisation

Un frontend Flutter de démonstration locale existe désormais (voir [FRONTEND.md](FRONTEND.md)). Un [socle backend local](BACKEND.md) existe aussi : Gateway, profils personnels persistants, inscription/connexion Keycloak, rôles user/admin, catalogue Skill/Hashtag, abonnements hashtags, bases séparées, migrations et CI. Les cases ci-dessous portent sur la livraison intégrée complète : une case n'est cochée qu'après vérification sur la pile intégrée. La cible de onze services est retenue ; l'ordre de livraison réduit le travail simultané sans fusionner les domaines.

Implementation status (2026-11-19): the connected Flutter feed reads Feed pages and persists likes/comments through Social. User now writes follow/block changes to an ordered Kafka outbox; Feed projects those relationships into Redis and caches versioned pages. Video now uses MongoDB transactions for documents and outbox; Social still uses PostgreSQL. Rating (U15) has its own service and PostgreSQL database; Chat (U21) has its own service, MongoDB history and WebSocket delivery. Account closure (U22) is orchestrated by User with a logged step per owning service. Notification consumes User, Video, Social and Chat events into an in-app inbox; push is ready but disabled without FCM credentials. Admin lists (A01/A02) and dashboard totals (A06) are served by the owning services; a new Analytics service counts daily activity from Kafka events (history starts with Kafka's 7-day retention). A checked box below was verified on the integrated local stack; the others stay open until that verification.

## Phase 0 : valider le socle

- [ ] Figer versions stables compatibles du JDK, Spring Boot/Cloud, Flutter et infrastructures.
- [ ] Créer monorepo, gabarit Spring Boot, conventions REST/événements et CI.
- [ ] Prototyper OIDC/PKCE sur mobile, upload direct et lecture vidéo sur appareils cibles.
- [ ] Valider transaction/outbox MongoDB avec replica set et consommateur idempotent.
- [ ] Définir taille/durée média, notation, règles de blocage, rétention et scénarios de charge.

## Phase 1 : MVP technique - six services logiques

Auth (Keycloak), User, Video, Feed, Interaction, Skill/Hashtag ; Gateway, Kafka, bases, stockage et workers FFmpeg constituent le socle de soutien.

- [ ] U01-U14 : comptes, profils, vidéo galerie/caméra, description/tags/skills, suppression, feed, commentaires/réponses et likes.
- [ ] U19-U20 : suivre/ne plus suivre utilisateurs et hashtags.
- [ ] A05 : interface admin minimale pour le catalogue de skills, avec rôle admin. Console React livrée dans `apps/admin` ; reste la validation sur la pile intégrée.
- [ ] Feed chronologique avec découverte simple ; fan-out hybride non requis dans cette première livraison.
- [x] Démontrer connexion -> upload -> transcodage -> publication -> interaction -> suppression. Scénario automatisé : [`scripts/demo-e2e.py`](DEMO.md), réussi sur la pile locale avec S3 réel le 18 novembre 2026.
- [x] Arrêter un worker et rejouer un événement ; vérifier absence de doublon et état utilisateur cohérent. Étape 6 de la démonstration, réussie le 18 novembre 2026.

Ce MVP sert à valider le socle ; il ne couvre pas encore tout le périmètre produit. Les signalements et la modération globale sont à livrer avant le lancement public envisagé.

## Phase 2 : complétude fonctionnelle et modération

- [x] Rating : U15, note de 1 à 5 par compétence, unique par évaluateur, modifiable et retirable ([service](../services/rating/README.md)). `RatingChanged` reste à émettre quand un consommateur existera.
- [ ] Moderation : U16/U17, A03/A04, état d'application et retrait média/CDN. Livré : signalement des vidéos et commentaires, file d'administration, retrait/rétablissement des vidéos (Video, Feed) et retrait des commentaires, décisions journalisées. Désactivation des comptes (Keycloak, profil, fil) et des hashtags livrée. Reste : service Moderation dédié, masquage des commentaires d'un compte désactivé.
- [x] User/Chat : U18, blocage effectivement appliqué (fil, likes, commentaires, notes et messages, vérifié auprès de User à chaque écriture) ; U21, persistance, reconnexion et déduplication ([service Chat](../services/chat/README.md)). Restent : état « lu » et événement MessageDelivered ; MessageAccepted alimente Notification.
- [x] U22 : fermeture de compte orchestrée avec confirmations et rétention définie ([sécurité](SECURITE_ET_MODERATION.md#fermeture-de-compte)). Vérifiée sur la pile intégrée (`scripts/smoke-closure.py`) le 19 novembre 2026. Restent : rétention des sauvegardes, réauthentification récente.
- [ ] Notification : push optionnel, distinct de la livraison des messages. Livré : [service Notification](../services/notification/README.md) alimenté par User, Video, Social et Chat (outbox), boîte et badge dans Flutter, effacement à la fermeture ; boîte vérifiée sur la pile intégrée (`scripts/smoke-notifications.py`) le 19 novembre 2026. Reste : push FCM sur appareil (projet Firebase, jeton dans Flutter), non vérifié.

## Phase 3 : administration complète puis évolutions

- [ ] Admin/Analytics : A01/A02/A06 avec pagination, auteurs et métriques définies. Livré et vérifié sur la pile intégrée (`scripts/smoke-admin.py`) : listes paginées des comptes, vidéos et commentaires avec auteurs (User, Video, Social), totaux exacts par service propriétaire, service Analytics alimenté par Kafka pour l'activité quotidienne, pages de la console React. Reste : validation de la console dans un navigateur sur la pile intégrée ; l'historique d'activité commence à la rétention Kafka (7 jours).
- [ ] Vérifier tous les U01-U22 et A01-A06 lors de la validation fonctionnelle.
- [ ] OpenSearch, feed hybride et métriques d'exposition si besoin confirmé.
- [ ] Multi-région seulement après décision sur réplication, résidence des données, écritures et conflits.

Le dashboard obligatoire n'est pas conditionné par les extensions OpenSearch ou multi-région : il doit être livré même si elles sont reportées.

## Déploiement et validation produit

- [ ] Compose de développement reproductible puis staging Kubernetes.
- [ ] Images versionnées, migrations, secrets externes, readiness et rollback vérifiés.
- [ ] Tests d'autorisation, doublons, événements tardifs, Kafka/Redis indisponibles et restauration.
- [ ] Mesurer les scénarios de qualité avec une charge déclarée.
- [ ] README avec commandes réellement vérifiées sur un checkout propre.
- [ ] Présentation : choix, alternatives, diagramme, ADRs, démonstration et limites constatées.
- [ ] Fournir aux parties prenantes les accès de staging appropriés et recueillir leurs retours.

## Organisation de l'équipe : responsabilités proposées

| Pôle | Responsabilité initiale | Relecture croisée |
| --- | --- | --- |
| Backend et identité | Identité, User, Skill, Gateway et contrats | Vidéo et données |
| Médias et expérience | Video, worker, Feed, Interaction et intégration mobile | Sécurité et contrats |

Répartir explicitement mobile/admin, tests et exploitation dans chaque itération ; faire tourner les revues. Aucun calendrier chiffré n'est promis sans connaître la disponibilité de l'équipe.
