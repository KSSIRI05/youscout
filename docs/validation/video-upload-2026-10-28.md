# Validation de l'upload vidéo — 28 octobre 2026

## Environnement

- Flutter web release, Microsoft Edge, origine `http://localhost:7357`.
- Gateway, Keycloak, User, Skill et Video en Docker Compose ; bases PostgreSQL séparées et services sains.
- Connexion AWS renouvelée avec le profil dédié au projet, puis vérification du rôle `YouScoutVideo`.
- Identifiants temporaires actualisés avec `scripts/prepare-video-aws.py`, hors Git, via l'override `compose.video-aws.yml`.

## Résultats

1. Le script `scripts/smoke-video-upload.py --put-object --complete --verify-restart` a réussi contre S3 réel : création de demande, PUT signé, confirmation par HEAD, redémarrage de Video et rejeu idempotent avec métadonnées inchangées. Les fixtures de ce script ont été supprimées de S3 et PostgreSQL.
2. Une requête OPTIONS réelle vers S3 a confirmé l'origine Flutter et le PUT avec l'en-tête `content-type`.
3. Après connexion Keycloak effectuée par l'utilisateur, l'onglet Vidéo a permis de sélectionner un MP4 synthétique de deux secondes, sans données personnelles, et de saisir un titre de test.
4. Flutter a transféré le fichier vers S3 puis affiché « Vidéo reçue et enregistrée » après confirmation backend.
5. Une consultation PostgreSQL a confirmé l'état `UPLOADED`, une taille de **70 180 octets**, ainsi que la présence de `completed_at` et `source_etag`.

Le clip du parcours Flutter est conservé dans le compte utilisé pour le test, avec le titre `YouScout E2E Flutter 2026-10-28`. Les comptes et données existants n'ont pas été supprimés. Aucun mot de passe, jeton, identifiant AWS temporaire ou URL présignée n'est enregistré ici.

## Limites

Cette validation porte sur le navigateur et le stockage du fichier source. Les parcours sur appareils Android/iOS, la reprise d'un gros fichier sur réseau instable, le contrôle du média par FFmpeg, les événements Kafka et la publication dans le fil restent à réaliser. `UPLOADED` confirme la réception du fichier ; ce statut ne signifie pas que la vidéo est prête à publier. Les identifiants AWS de développement expirent et doivent être renouvelés pour de futurs essais.
