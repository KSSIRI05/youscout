# Caractéristiques et priorités

## Exigences explicites et contraintes

| Objectif produit | Conséquence |
| --- | --- |
| Milliers puis potentiellement millions d'utilisateurs | Scalabilité |
| Expansion géographique progressive | Portée internationale |
| Chat instantané | Besoin fonctionnel temps réel |
| Mobile cross-plateforme | Contrainte de réalisation |
| Signalements, blocages et désactivations | Capacités de modération |
| Connexion par compte tiers | Interopérabilité d'identité |
| Visibilité et égalité des chances | Objectif métier d'équité |
| Dashboard admin | Reporting métier, distinct des métriques techniques |
| Déployer l'application | Déployabilité attendue |
| Budget non contraint | Contrainte économique, pas un attribut de qualité |

## Caractéristiques déduites

Élasticité pour absorber des pics, faible latence pour le feed, disponibilité, isolation des pannes, sécurité, confidentialité, fiabilité, maintenabilité, testabilité, observabilité technique et internationalisation. Les pics chiffrés, langues de lancement et SLA restent à définir ; le multi-région sera évalué selon les besoins mesurés.

## Top 5 retenu

1. Scalabilité et élasticité : réplication indépendante des services et workers.
2. Disponibilité et tolérance aux pannes : chat isolé, dépendances avec timeouts, dégradation contrôlée.
3. Performance : upload direct et diffusion CDN, lectures pré-calculées.
4. Évolutivité et déployabilité : contrats versionnés et déploiements indépendants.
5. Sécurité et protection des données : obligations transversales dès le socle, même si placées au cinquième rang de comparaison.

La cohérence à terme convient aux projections du feed et aux compteurs. Les écritures de compte et de note sont transactionnelles dans leur service ; cela ne constitue pas une transaction globale entre services. Le retrait de contenu et la révocation d'accès exigent un traitement prioritaire explicite.

## Scénarios de qualité proposés, non mesurés

| Scénario | Cible initiale à vérifier |
| --- | --- |
| Requêtes API ordinaires, hors transfert média | p95 inférieur à 500 ms, région et charge déclarées |
| Chat entre clients connectés dans une région | p95 inférieur à 1 s |
| Disponibilité des API | 99,9 % mensuel, périmètre et exclusions à définir |
| Worker interrompu après transcodage | Reprise sans publication en double |
| Kafka indisponible | Outbox conservée ; upload en attente, sans annonce de publication réussie |
| Vidéo désactivée | Refus immédiat de nouvelles autorisations ; accès déjà signé limité à une durée maximale documentée |
| Redis perdu | Reconstruction du feed et repli limité à des lectures paginées |

Définir avant les essais le nombre d'utilisateurs simultanés, le débit de publications, les tailles vidéo et le taux d'erreur admissible. Mesurer temps de démarrage vidéo, rebuffering et retard des consommateurs. Les scores comparatifs ne remplacent pas ces mesures. Voir [ADR-001](adr/ADR-001-caracteristiques-prioritaires.md).
