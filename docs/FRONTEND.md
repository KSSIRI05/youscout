# État du frontend

Le frontend Flutter propose désormais deux parcours indépendants : une connexion au backend et une démo sociale locale. Voir [les instructions détaillées](../apps/mobile/README.md).

| Disponible avec le compte réel | Encore local ou à développer |
| --- | --- |
| Inscription et connexion Keycloak, Authorization Code + PKCE | Connexion sociale, SMTP et validation sur appareils |
| Profil personnel persistant : lecture, nom et biographie | Autres profils publics et fonctionnalités sociales |
| Catalogue des compétences avec recherche et pagination | Attribution des compétences aux vidéos |
| Hashtags, création et abonnements personnels persistants | Fil personnalisé alimenté par les abonnements |
| Renouvellement, erreurs réseau/403/401 et déconnexion | Stockage de session durable entre relancements |
| Adaptateurs web et AppAuth natif | Builds et parcours Android/iOS sur appareil |
| Sélection, titre, PUT S3 avec progression et confirmation Video, validés sur le web avec le vrai bucket | Validation sur appareils, transcodage, lecture et publication |

Les vidéos, likes, commentaires, évaluations, blocages, messages et signalements de la démo restent en mémoire. Ils ne sont pas mélangés avec l’identité réelle. L'onglet Vidéo du compte connecté prépare un upload persistant via Video, transfère le fichier à S3 et demande sa confirmation. Le statut confirmé `UPLOADED` ne déclenche encore ni traitement ni publication dans le fil. Les URL signées et les jetons ne sont pas conservés sur disque. Les autres services métier restent à développer.

Les jetons du compte sont conservés en mémoire seulement. Le navigateur garde temporairement l’état PKCE dans sessionStorage jusqu’au retour de Keycloak, puis le supprime. Un rechargement web exige une nouvelle connexion, éventuellement facilitée par la session SSO Keycloak.

Le [test réel du 28 octobre 2026](validation/video-upload-2026-10-28.md) a confirmé l'envoi d'un clip depuis Flutter web jusqu'à l'état PostgreSQL `UPLOADED`, ainsi que le rejeu backend après redémarrage. Aucun jeton ni URL signée n'est conservé dans ce compte rendu.

Validation locale de cette intégration : 29 tests Flutter réussis, analyse statique sans problème et build web release réussi. Un parcours navigateur avec la pile Docker réelle a confirmé l’inscription, le retour PKCE, la modification du profil, le catalogue, la création et le suivi/désabonnement d’un hashtag, puis leur persistance après déconnexion/reconnexion. Un 401 unique injecté dans le navigateur a déclenché un véritable renouvellement Keycloak et le rejeu réussi de la requête. Un callback sans état PKCE a été refusé avant tout appel API. Le compte temporaire, son profil, ses abonnements et son hashtag de test ont été supprimés. Ces essais ne remplacent pas une validation sur appareils Android/iOS.
