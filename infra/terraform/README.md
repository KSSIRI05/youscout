# Stockage vidéo S3 — Terraform

Ce dossier prépare un bucket privé pour le futur service Video de YouScout. Il ne déploie pas encore le service Java, les workers, CloudFront ou le frontend. Le rôle IAM existant est réutilisé, sans modifier ses permissions.

## Ressources prévues

Avec les valeurs par défaut, le bucket se nomme `youscout-014663857971-dev-videos`, dans `us-east-1`. Le nom respecte le préfixe autorisé par [le bootstrap IAM](../iam/README.md).

| Ressource Terraform | Utilité |
| --- | --- |
| `aws_s3_bucket.videos` | Bucket généraliste privé, sans destruction forcée |
| `aws_s3_bucket_public_access_block.videos` | Les quatre protections contre l'accès public |
| `aws_s3_bucket_ownership_controls.videos` | Propriété du bucket et ACL désactivées |
| `aws_s3_bucket_server_side_encryption_configuration.videos` | Chiffrement par défaut SSE-S3, AES256 |
| `aws_s3_bucket_cors_configuration.videos` | GET, HEAD et PUT depuis les origines Flutter déclarées |
| `aws_s3_bucket_lifecycle_configuration.videos` | Abandon des multipart inachevés après le délai configuré |
| `aws_s3_bucket_policy.https_only` | Refus du transport HTTP pour les accès non effectués directement par un service AWS |

La politique HTTPS ne contient aucun Allow et ne publie aucun objet. Le compte et le rôle sont vérifiés : un plan utilisant l'identité root ou un autre compte est refusé. Les objets sont destinés à des URL présignées ; CORS ne donne pas d'autorisation S3 et ne constitue pas un contrôle d'accès pour les applications natives.

## Prérequis et configuration

- Terraform `>= 1.10, < 2`, fournisseur AWS `6.x` ; la version exacte et ses empreintes sont enregistrées dans `.terraform.lock.hcl`, à versionner.
- Profil `youscout` fonctionnel, assumant `YouScoutTerraform`, et session active du profil source `youscout-login`.
- Profil de compatibilité `youscout-terraform` pour le fournisseur Terraform, décrit ci-dessous.
- Les origines locales par défaut sont `http://localhost:7357` et `http://127.0.0.1:7357`, comme dans Keycloak et la Gateway. Les autres origines doivent être HTTPS et sans chemin. Ajuster aussi la configuration de l'application et de ses autres endpoints si l'hébergement change.

Toutes les variables ont une valeur par défaut. Pour personnaliser l'environnement, copier `terraform.tfvars.example` vers `terraform.tfvars` et éditer ce dernier. Ne jamais y ajouter de clé AWS ou de jeton. Les profils locaux ne sont pas portables vers la CI : une future CI de déploiement utilisera sa propre authentification OIDC et une configuration adaptée.

## Vérifier sans créer de ressources

Dans PowerShell, depuis la racine du dépôt :

```powershell
aws sts get-caller-identity --profile youscout
cd infra/terraform
terraform init -input=false
terraform fmt -check -recursive
terraform validate
terraform test
terraform plan -input=false "-out=youscout.tfplan"
terraform show -no-color youscout.tfplan
```

Le fournisseur utilise explicitement `aws_profile = "youscout-terraform"` ; il n'est pas nécessaire de changer le profil AWS global. Si la session a expiré, renouveler d'abord la connexion du profil source `youscout-login`.

### Compatibilité avec la session `aws login`

Lors de la validation locale, AWS CLI savait assumer le rôle mais le fournisseur AWS `6.69.1` ne parvenait pas à charger directement le profil basé sur `login_session`. Le profil distinct `youscout-terraform`, déjà configuré sur ce poste, utilise donc le mécanisme officiel `credential_process` pour déléguer la résolution des identifiants à AWS CLI. Il conserve le même rôle et les mêmes permissions : aucune clé permanente n'est ajoutée.

Pour recréer cette passerelle sur un autre poste où `aws` est disponible dans le PATH :

```powershell
aws configure set region us-east-1 --profile youscout-terraform
aws configure set credential_process 'aws configure export-credentials --profile youscout --format process' --profile youscout-terraform
aws sts get-caller-identity --profile youscout-terraform
```

Le profil de destination doit rester distinct du profil source `youscout` pour éviter une récursion. Sur le poste actuel, la commande de résolution utilise le chemin absolu local d'AWS CLI afin de fonctionner aussi dans les processus qui n'ont pas encore rechargé le PATH. Les identifiants temporaires passent directement au fournisseur ; ne pas exécuter `export-credentials` seul pour afficher ou copier ses résultats. Voir [la documentation AWS](https://docs.aws.amazon.com/cli/latest/reference/configure/export-credentials.html).

`terraform test` utilise exclusivement un fournisseur AWS simulé et des commandes `plan` : il ne déploie rien et n'a pas besoin d'une session AWS. Il vérifie les réglages privés, le chiffrement, la politique HTTPS, le périmètre CORS, l'absence d'expiration des vidéos et le rejet des paramètres/identités non autorisés. Le véritable `terraform plan` appelle AWS en lecture pour vérifier l'identité ; il ne teste pas chaque permission d'écriture ni l'unicité mondiale du nom au moment de sa future création.

## Créer les ressources après examen du plan

La commande suivante applique les actions enregistrées dans le plan :

```powershell
terraform apply youscout.tfplan
terraform output
```

Un plan sauvegardé s'applique directement : l'examiner avant d'exécuter cette commande. Ne pas versionner le plan. Régénérer et vérifier le plan si la configuration, les dépendances ou les valeurs changent. La préparation de ce dossier n'exécute pas `apply`.

## État, données et suppression

L'état est local (`terraform.tfstate`) et doit être conservé avec sa sauvegarde : le perdre ferait perdre le suivi des ressources. Les fichiers d'état, les plans et les tfvars personnels sont ignorés par Git. Le bucket vidéo n'est pas le backend Terraform. Avant un usage collaboratif, prévoir un backend distant dédié avec contrôle d'accès et verrouillage ; ne pas partager des copies concurrentes du même état local.

`prevent_destroy = true` bloque les plans qui détruiraient/remplaceraient ce bucket tant que ce bloc existe. Ce n'est pas une rétention AWS : retirer le bloc complet du code contourne cette protection. `force_destroy = false` refuse de vider automatiquement un bucket à sa suppression. La destruction doit être préparée comme une opération distincte, avec traitement explicite des données.

Le nettoyage lifecycle cible uniquement les parties multipart non finalisées, pas les vidéos terminées ni les objets chargés par un PUT simple. Le service Video devra gérer quotas, clés uniques, confirmation de fin et nettoyage des objets complets abandonnés. Aucun versioning, Object Lock, réplication ou expiration des vidéos n'est activé ici ; ce premier stockage ne constitue pas une politique de sauvegarde. Une suppression autorisée d'objet reste définitive.

## Raccordement à venir

Les sorties donnent le nom, l'ARN et la région à fournir au futur service Video. Le rôle de provisionnement Terraform ne doit pas être embarqué dans Flutter. Le [rôle Video dédié](../iam/VIDEO.md), `YouScoutVideo`, est créé pour l'envoi et la lecture en développement local ; sa confiance Kubernetes et les permissions propres aux workers restent à définir lors de leur déploiement. Le futur backend générera les URL présignées d'upload PUT et de lecture, après contrôle du JWT et du propriétaire. Ne pas ajouter d'ACL `public-read` aux uploads : les ACL sont désactivées.

## Coûts et références

Le stockage des vidéos, les requêtes et les transferts sortants peuvent être facturés après déploiement et utilisation. Le nettoyage des parties multipart abandonnées réduit leur conservation inutile. Aucune clé KMS, classe d'archivage, réplication, distribution CDN ni journalisation payante supplémentaire n'est créée par cette configuration. Consulter les [tarifs S3](https://aws.amazon.com/s3/pricing/) pour la région et le volume effectifs.

Sources : [sécurité S3](https://docs.aws.amazon.com/AmazonS3/latest/userguide/security-best-practices.html), [politique HTTPS](https://docs.aws.amazon.com/AmazonS3/latest/userguide/amazon-s3-policy-keys.html), [nettoyage multipart](https://docs.aws.amazon.com/AmazonS3/latest/userguide/mpu-abort-incomplete-mpu-lifecycle-config.html), [tests Terraform simulés](https://developer.hashicorp.com/terraform/language/tests/mocking).

## Résultat de déploiement

Vérifié avec Terraform `1.16.6` et le fournisseur AWS `6.69.1` : formatage correct, `validate` réussi et sept tests simulés réussis. Le 18 octobre 2026, le plan authentifié sous le rôle YouScout a été appliqué : **7 ressources ajoutées, 0 modifiée et 0 détruite**, pour le bucket `youscout-014663857971-dev-videos` en `us-east-1` et ses six configurations. Les lectures AWS confirment le blocage des accès publics, le chiffrement AES256, les ACL désactivées, la politique HTTPS, le CORS et le nettoyage multipart après un jour. Le plan de contrôle après déploiement ne détecte aucune différence (`No changes`, code de sortie 0). L'état local et le fichier `youscout.tfplan` sont ignorés par Git ; conserver l'état et sa sauvegarde pour les prochaines opérations.
