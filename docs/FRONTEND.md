# État du frontend

Le code mobile se trouve dans [apps/mobile](../apps/mobile/README.md). Il s’agit d’une démonstration Flutter interactive locale, première étape de l’architecture retenue, et non du MVP distribué complet.

| Livré | À intégrer |
| --- | --- |
| Thème, navigation et composants inspirés de Figma | Auth Keycloak avec OIDC/PKCE |
| Feed, recherche, profils et abonnements locaux | API Gateway, pagination et persistance |
| Likes, commentaires, notes et signalements en mémoire | Autorisations serveur, modération et règles métier définitives |
| Sélection et prévisualisation vidéo | Upload S3, statut de traitement et CDN |
| Messages locaux | Chat persistant, reconnexion et livraison |
| Tests unitaires/widget et workflow CI web | Tests sur appareils, intégration et administration React |

Validation locale : analyse statique sans problème, 11 tests réussis et build web release réussi sur Flutter 3.47.7 / Dart 3.13.5. Les plateformes Android/iOS sont générées mais non compilées ni validées sur appareil. Le résultat du workflow GitHub reste à consulter après publication.

La frontière `ScoutRepository` permet de remplacer les données de démonstration. Les interactions sont gérées dans `ScoutStore` ; leur raccordement aux API exigera des états de chargement, d’erreur, d’expiration de session et des validations côté serveur. Aucune exigence de cohérence distribuée, sécurité ou performance n’est prouvée par cette démo.
