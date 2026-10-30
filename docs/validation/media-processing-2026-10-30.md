# Validation du traitement vidéo — 30 octobre 2026

Le test `scripts/smoke-processing.py` a validé la chaîne backend avec le vrai bucket S3 privé du projet et la pile Docker locale. Le worker utilisait l'image FFmpeg reconstruite avec les corrections de durée et de dimensions des miniatures.

## Résultats

- Obtention d'un jeton technique Keycloak, puis création d'un ticket d'upload via la Gateway.
- Envoi d'un clip synthétique de deux secondes par URL présignée S3 et confirmation `UPLOADED`.
- Réception de `VideoUploaded` par Kafka, exécution du worker et tâche persistée `SUCCEEDED`.
- Téléchargement S3 conditionnel, transcodage MP4 et génération d'une miniature JPEG.
- Publication du résultat par l'outbox du worker et réception dans Video : `GET /v1/videos/{id}` renvoie `READY` avec durée et dimensions positives.
- Vérification S3 des deux fichiers dérivés : tailles non nulles et types `video/mp4` et `image/jpeg`.
- Rejeu de la confirmation d'upload : réponse identique après traitement.
- Suppression des objets S3 et des lignes de base propres au test. Les messages Kafka suivent la rétention configurée.

Le script s'est terminé avec le code de sortie zéro. Les sept tests Python ont également réussi. Les sept tests FFmpeg, dont les deux régressions sur la durée maximale et les vidéos étroites, avaient réussi après correction.

## Portée

Cette validation exerce les API backend via la Gateway ; elle ne valide pas un nouveau parcours de lecture dans Flutter. La lecture privée du MP4 et de la miniature depuis Flutter reste à développer.

Les identifiants temporaires sont conservés dans le dossier local ignoré `infra/compose/.aws`, sans secret ni URL présignée dans ce compte rendu. La pile de traitement reste démarrée ; renouveler les identifiants avant leur expiration pour poursuivre les essais.
