# Démonstration de bout en bout

`scripts/demo-e2e.py` rejoue le parcours du MVP demandé par la [roadmap](ROADMAP.md) sur la pile locale complète, avec le vrai bucket S3 :

| Étape | Ce qui est vérifié |
| --- | --- |
| 1. Connexion | Jeton Keycloak (compte technique, rôle `user`) et profil lu via la Gateway |
| 2. Upload | Ticket signé, `PUT` direct vers S3 privé, confirmation `UPLOADED` |
| 3. Transcodage | Le worker télécharge la source, produit MP4 et miniature avec FFmpeg, le résultat revient à Video par Kafka : `READY` |
| 4. Publication | `PUBLISHED`, l'événement atteint Feed, la vidéo apparaît dans le fil, les liens de lecture publique sont émis |
| 5. Interactions | Un like compté une fois et un commentaire, après vérification de la vidéo par Social |
| 6. Robustesse | Worker arrêté, `VideoUploaded` et `VideoPublished` renvoyés dans Kafka avec leur `eventId` d'origine, worker redémarré : toujours une seule tâche `SUCCEEDED`, aucune nouvelle tentative ni résultat, état Video inchangé, une seule entrée dans le fil |
| 7. Suppression | `204` (y compris au second appel), disparition de la bibliothèque et du fil, like refusé, objets S3 source et traités supprimés par le worker |

Chaque étape affiche `PASS`. Les données créées (MongoDB, bases PostgreSQL, objets S3) sont supprimées à la fin, même après un échec ; les messages Kafka restent jusqu'à expiration de la rétention. Aucun jeton, URL signée ni identifiant AWS n'est affiché.

## Lancer

Depuis la racine, Docker démarré, avec une session AWS du projet valide (`aws login --profile youscout-login`) :

```powershell
python infra/compose/init-env.py --upgrade
python scripts/prepare-video-aws.py
docker compose -f infra/compose/compose.yml -f infra/compose/compose.kafka.yml -f infra/compose/compose.worker.yml -f infra/compose/compose.processing.yml -f infra/compose/compose.video-aws.yml -f infra/compose/compose.processing-aws.yml --profile video --profile worker --profile feed up -d --build --wait --wait-timeout 600
python scripts/apply-keycloak.py
python scripts/demo-e2e.py
```

`prepare-video-aws.py` écrit des identifiants temporaires (environ une heure) dans `infra/compose/.aws`, ignoré par Git ; le relancer avant expiration. Le rôle `YouScoutVideo` doit disposer de `s3:DeleteObject` ([infra/iam/VIDEO.md](../infra/iam/VIDEO.md)) pour l'étape 7.

## Limites

- La démonstration utilise le compte technique `youscout-smoke` plutôt qu'une connexion par navigateur ; le parcours navigateur (PKCE) est couvert par `smoke-identity.py`.
- Elle n'est pas exécutée par la CI, qui n'a pas d'accès AWS. La CI couvre séparément chaque maillon sans S3 réel (`smoke-outbox`, `smoke-worker`, `smoke-deletion`, tests d'intégration).
