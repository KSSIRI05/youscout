# Accès AWS dédié à YouScout

Ces politiques correspondent au bootstrap IAM créé via AWS CLI dans le compte `014663857971`. Elles sont lisibles et ne contiennent aucun secret. Elles ne constituent pas encore une configuration Terraform et ne sont pas automatiquement réappliquées lorsqu’un fichier change.

## Identités

| Élément | Valeur |
| --- | --- |
| Utilisateur humain | `arn:aws:iam::014663857971:user/youscout/youscout-developer` |
| Rôle de provisionnement S3 | `arn:aws:iam::014663857971:role/youscout/YouScoutTerraform` |
| Profil de connexion navigateur | `youscout-login` |
| Profil du rôle pour AWS CLI | `youscout` |
| Profil de compatibilité du fournisseur Terraform | `youscout-terraform`, délègue à AWS CLI via `credential_process` |
| Région autorisée | `us-east-1` |
| Buckets autorisés | `youscout-014663857971-*`, dans ce compte |
| Durée maximale des sessions du rôle | Une heure |

Le profil `default` existant est conservé. Aucun secret d’accès permanent n’a été créé pour l’utilisateur dédié. Le mot de passe initial a été changé et le fichier local de première connexion supprimé. Ne jamais enregistrer de mot de passe ou d'identifiants temporaires dans le dépôt.

## Politiques

- [developer-policy.json](developer-policy.json) permet d’assumer seulement le rôle YouScout, de consulter son propre utilisateur et de changer son propre mot de passe. La lecture de la politique de mot de passe du compte utilise `Resource: "*"`, cette action ne possédant pas de ressource ciblable.
- La politique gérée AWS `SignInLocalDevelopmentAccess` permet la connexion CLI par navigateur. Elle ne donne pas de droits S3 ou d’administration IAM.
- [terraform-trust-policy.json](terraform-trust-policy.json) autorise uniquement l’utilisateur dédié à assumer le rôle. Elle n’autorise pas tous les utilisateurs du compte.
- [terraform-s3-policy.json](terraform-s3-policy.json) définit les opérations S3 du rôle. La création est limitée aux noms du projet et à la région. Les autres opérations exigent aussi que le bucket appartienne au compte. Le rôle n’a ni droits IAM, ni droits EC2, ni accès aux buckets d’autres projets.

Ce rôle permet de provisionner les futurs buckets et leurs configurations, ainsi que de gérer leurs objets. Il pourra donc supprimer les données du projet : il est destiné au développement et à Terraform, pas à être distribué au frontend. Le [rôle Video dédié](VIDEO.md), `YouScoutVideo`, permet uniquement l'envoi et la lecture d'objets du bucket vidéo de développement. Les futures ressources Terraform doivent respecter le préfixe défini ici.

## Première connexion

1. Ouvrir `https://014663857971.signin.aws.amazon.com/console/` avec l’utilisateur IAM `youscout-developer`, puis remplacer son mot de passe initial.
2. Authentifier `youscout-login` par le navigateur avec `aws login --profile youscout-login`. Choisir cette identité IAM, pas le compte racine.
3. Vérifier l’identité du profil du rôle :

```powershell
aws sts get-caller-identity --profile youscout
```

L’ARN attendu contient `assumed-role/YouScoutTerraform/`. Les profils sont configurés localement dans `~/.aws/config`. La CLI renouvelle les identifiants du rôle à partir de la session du profil de connexion. La [configuration Terraform](../terraform/README.md) réutilise ces identifiants via le profil de compatibilité `youscout-terraform`.

Pour utiliser ce profil dans un terminal dédié au projet :

```powershell
$env:AWS_PROFILE = "youscout"
aws sts get-caller-identity
# Après création de la configuration Terraform : terraform init, validate, plan.
```

## Validation du bootstrap

AWS IAM Access Analyzer a validé les trois documents sans anomalie. Le simulateur IAM, appliqué au rôle créé, a confirmé la création/configuration de buckets et l’upload dans le périmètre YouScout, ainsi que le refus hors projet, hors région, pour l’administration IAM et pour EC2. Il s’agit de simulations : la vérification réelle du profil doit être effectuée après la première connexion navigateur. Aucun bucket S3 n’a été créé pour ces contrôles.

Les noms des 43 actions S3 ont été vérifiés avec la référence d’autorisation AWS ; les actions soumises à `s3:ResourceAccount` prennent en charge cette condition. Les contrôles ne prouvent pas encore qu’un futur plan Terraform complet dispose de toutes les permissions nécessaires : il faudra ajuster précisément les droits si le plan ajoute de nouveaux types de ressources.

Références : [connexion CLI](https://docs.aws.amazon.com/signin/latest/userguide/command-line-sign-in.html), [profils de rôle](https://docs.aws.amazon.com/cli/latest/userguide/cli-configure-role.html), [politique SignInLocalDevelopmentAccess](https://docs.aws.amazon.com/aws-managed-policy/latest/reference/SignInLocalDevelopmentAccess.html).
