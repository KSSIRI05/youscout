# Lecture privée dans Flutter

L'onglet Vidéo comprend l'envoi d'un clip et **Mes vidéos**, une liste paginée propre au compte connecté. Les vidéos reçues affichent leur traitement en cours, leur disponibilité ou un échec. La page visible est actualisée toutes les quatre secondes tant qu'elle contient un traitement en attente ; le bouton d'actualisation permet de reprendre après une erreur réseau.

Une vidéo prête ouvre un lecteur avec miniature, lecture/pause et barre de progression. Le changement d'onglet, la fermeture du lecteur et la déconnexion libèrent le lecteur. La liste est rechargée à la réouverture de l'onglet et après confirmation d'un upload.

## API et confidentialité

- `GET /v1/videos?offset=0` : vingt vidéos du propriétaire au maximum, triées de la plus récente à la plus ancienne, avec `hasMore`. Aucune URL dans cette liste.
- `GET /v1/videos/{id}` : métadonnées et état privés.
- `GET /v1/videos/{id}/playback` : pour une vidéo `UPLOADED` et `READY`, renvoie `videoUrl`, `thumbnailUrl` et `expiresAt`.

La Gateway et Video exigent Keycloak et un rôle `user` ou `admin`. Video impose le `sub` propriétaire ; un administrateur ne peut pas lire les vidéos d'un autre compte. Les réponses portent `Cache-Control: no-store`. La signature vérifie les références des fichiers dérivés et ne donne jamais accès à la source d'upload.

Les GET S3 sont signés pour 120 secondes ; des identifiants AWS expirés ou révoqués peuvent réduire cette durée. Flutter renouvelle le ticket quinze secondes avant son expiration, recrée le lecteur et reprend à la position précédente. Une erreur propose de réessayer. Aucun Bearer Keycloak n'est envoyé à S3 ; les URL restent en mémoire et ne sont pas incluses dans les messages d'erreur affichés.

Ces URL donnent accès au média à leur détenteur jusqu'à leur expiration : la déconnexion ferme le lecteur et efface son état, mais ne révoque pas instantanément un lien déjà délivré. Le bucket reste privé et sa configuration CORS existante autorise GET et Range pour les origines Flutter configurées. Les lectures utilisent les requêtes et le transfert S3 habituels ; aucune nouvelle ressource AWS n'est provisionnée.

## Vérification

Les tests Video couvrent le filtrage par propriétaire, la pagination, les refus avant `READY`, la signature limitée aux fichiers dérivés et les erreurs AWS sans diagnostic sensible. Les tests Flutter couvrent les états, le polling, l'expiration des tickets, le renouvellement et la position du lecteur, l'absence de Bearer côté média et la libération à la fermeture.

Le script `scripts/smoke-processing.py` vérifie également les liens privés par GET S3 partiel (`206`) sur le MP4 et la miniature, puis supprime ses données temporaires. Les instructions de démarrage figurent dans [le guide du traitement](MEDIA_PROCESSING.md).

Validation du 30 octobre 2026 : 48 tests Video, 15 tests Gateway et 47 tests Flutter réussis ; analyse Flutter sans problème et build web release réussi. Le smoke complet avec le vrai S3 a réussi, y compris la liste privée, les GET partiels sans Bearer, le rejeu de confirmation et le nettoyage. Le contrôle visuel dans un navigateur et les essais sur appareils Android/iOS restent à confirmer ; l'outil navigateur était indisponible pendant cette validation.
