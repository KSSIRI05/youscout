# Accès S3 du service Video

## Rôle de développement

Le rôle `arn:aws:iam::014663857971:role/youscout/YouScoutVideo` est distinct de `YouScoutTerraform`. Son unique politique intégrée, `YouScoutVideoS3Access`, est définie dans [video-s3-policy.json](video-s3-policy.json).

Il permet seulement `s3:PutObject` et `s3:GetObject` sur `arn:aws:s3:::youscout-014663857971-dev-videos/*`, dans le compte du projet et la région `us-east-1`, via HTTPS. `HeadObject` utilise la permission `GetObject`. Cela prépare les URL présignées PUT et GET et la confirmation de présence d'un objet. L'envoi peut aussi remplacer un objet existant : le futur backend devra générer des clés uniques et vérifier le propriétaire depuis le JWT.

Il ne permet ni la suppression, ni le listing du bucket, ni les changements de politique/ACL, ni l'administration IAM. Les uploads simples n'envoient ni ACL ni tags. Le chiffrement AES256 est fourni par le bucket. La gestion multipart avancée et ses éventuelles permissions supplémentaires seront définies lorsqu'elle sera implémentée.

La [relation de confiance](video-trust-policy.json) autorise uniquement `youscout-developer`, pour les essais locaux. La [politique utilisateur supplémentaire](video-developer-policy.json), nommée `YouScoutVideoDevelopmentAccess`, autorise l'utilisation de ce rôle sans remplacer les droits Terraform existants. Les sessions durent au maximum une heure. Aucun identifiant permanent n'est créé.

## Profils locaux

Avec une session `youscout-login` valide :

```powershell
aws configure set role_arn arn:aws:iam::014663857971:role/youscout/YouScoutVideo --profile youscout-video
aws configure set source_profile youscout-login --profile youscout-video
aws configure set role_session_name youscout-video-local --profile youscout-video
aws configure set region us-east-1 --profile youscout-video
aws sts get-caller-identity --profile youscout-video
```

Le poste de développement dispose aussi de `youscout-video-sdk`, un profil `credential_process` qui délègue la résolution des identifiants temporaires à AWS CLI. Il prépare la compatibilité avec le futur SDK Java, comme le profil Terraform existant. Pour le recréer sur un poste où `aws` est dans le PATH :

```powershell
aws configure set credential_process 'aws configure export-credentials --profile youscout-video --format process' --profile youscout-video-sdk
aws configure set region us-east-1 --profile youscout-video-sdk
```

Ne pas afficher les résultats de `export-credentials`. Le [service Java Video](../../services/video/README.md) sélectionne `youscout-video-sdk` avec le profil Spring `local`. Il crée les métadonnées PostgreSQL et les URL PUT temporaires via `POST /v1/videos/uploads`, avec propriétaire issu du JWT. `POST /v1/videos/{id}/complete` utilise `HeadObject` pour confirmer taille et type déclarés, avec les droits `GetObject` existants. La lecture métier reste à implémenter. Le préparateur `scripts/prepare-video-aws.py` et l'override Compose local transmettent uniquement ce rôle temporaire à Docker, via un dossier privé exclu de Git et des images. Ne jamais copier ces profils ou des identifiants AWS dans Flutter.

## Gestion et déploiement futur

Ces documents sont un bootstrap IAM via AWS CLI, hors de l'état Terraform S3. Leur édition seule ne modifie pas AWS. La création et la mise à jour nécessitent une identité administratrice IAM : le rôle `YouScoutTerraform` n'est pas élargi pour gérer IAM. Le profil administrateur existant sert uniquement à ce bootstrap ; les opérations S3 du backend doivent utiliser le rôle Video.

Pour un cluster Kubernetes AWS, remplacer la confiance de développement par une identité de workload liée au cluster et au compte de service Video (IRSA ou EKS Pod Identity selon le choix de déploiement). Aucun cluster ni fournisseur OIDC n'existe encore dans cette configuration ; aucune confiance ECS, EC2 ou Kubernetes générique n'est ajoutée.

## Validation du 18 octobre 2026

Les trois documents ont été validés par IAM Access Analyzer sans anomalie. Le profil `youscout-video-sdk` assume effectivement `YouScoutVideo`. Un envoi puis une lecture réels ont confirmé le contenu du fichier temporaire ; le listing a été refusé par S3. Le fichier a ensuite été supprimé avec le rôle de provisionnement, car le rôle Video ne permet pas la suppression. Les simulations du rôle confirment le refus de suppression, de modification d'ACL, d'accès à un autre bucket/compte/région, de HTTP et d'administration du bucket ou d'IAM. Ces simulations ne remplacent pas les futurs tests d'autorisation du backend.

Références : [profils de rôle AWS CLI](https://docs.aws.amazon.com/cli/latest/userguide/cli-configure-role.html), [permissions des URL présignées](https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-presigned-url.html), [référence d'autorisation S3](https://docs.aws.amazon.com/service-authorization/latest/reference/list_amazons3.html).
