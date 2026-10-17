# Roadmap de réalisation

Un frontend Flutter de démonstration locale existe désormais (voir [FRONTEND.md](FRONTEND.md)). Un [socle backend local](BACKEND.md) existe aussi : Gateway, profils personnels persistants, inscription/connexion Keycloak, rôles user/admin, catalogue Skill/Hashtag, abonnements hashtags, bases séparées, migrations et CI. Les cases ci-dessous portent sur la livraison intégrée complète et restent ouvertes. La cible de onze services est retenue ; l'ordre de livraison réduit le travail simultané sans fusionner les domaines.

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
- [ ] A05 : interface admin minimale pour le catalogue de skills, avec rôle admin.
- [ ] Feed chronologique avec découverte simple ; fan-out hybride non requis dans cette première livraison.
- [ ] Démontrer connexion -> upload -> transcodage -> publication -> interaction -> suppression.
- [ ] Arrêter un worker et rejouer un événement ; vérifier absence de doublon et état utilisateur cohérent.

Ce MVP sert à démontrer le socle ; il ne couvre pas encore la totalité du sujet. Les signalements et la modération globale sont à livrer avant le lancement public envisagé.

## Phase 2 : complétude fonctionnelle et modération

- [ ] Rating : U15, unicité et modification de note.
- [ ] Moderation : U16/U17, A03/A04, état d'application et retrait média/CDN.
- [ ] User/Chat : U18, blocage effectivement appliqué ; U21, persistance, reconnexion et déduplication.
- [ ] U22 : fermeture de compte orchestrée avec confirmations et rétention définie.
- [ ] Notification : push optionnel, distinct de la livraison des messages.

## Phase 3 : administration complète puis évolutions

- [ ] Admin/Analytics : A01/A02/A06 avec pagination, auteurs et métriques définies.
- [ ] Vérifier tous les U01-U22 et A01-A06 pour le rendu complet.
- [ ] OpenSearch, feed hybride et métriques d'exposition si besoin confirmé.
- [ ] Multi-région seulement après décision sur réplication, résidence des données, écritures et conflits.

Le dashboard obligatoire n'est pas conditionné par les extensions OpenSearch ou multi-région : il doit être livré même si elles sont reportées.

## Déploiement et soutenance

- [ ] Compose de développement reproductible puis staging Kubernetes.
- [ ] Images versionnées, migrations, secrets externes, readiness et rollback vérifiés.
- [ ] Tests d'autorisation, doublons, événements tardifs, Kafka/Redis indisponibles et restauration.
- [ ] Mesurer les scénarios de qualité avec une charge déclarée.
- [ ] README avec commandes réellement vérifiées sur un checkout propre.
- [ ] Présentation : choix, alternatives, diagramme, ADRs, démonstration et limites constatées.
- [ ] Partager le dépôt et les accès de démonstration appropriés avec les évaluateurs.

## Équipe de quatre : responsabilités proposées

| Binôme | Responsabilité initiale | Relecture croisée |
| --- | --- | --- |
| 1 et 2 | Identité, User, Skill, Gateway et contrats | Vidéo et données |
| 3 et 4 | Video, worker, Feed, Interaction et intégration mobile | Sécurité et contrats |

Répartir explicitement mobile/admin, tests et exploitation dans chaque itération ; faire tourner les revues. Aucun calendrier chiffré n'est promis sans connaître la disponibilité de l'équipe.
