variable "aws_account_id" {
  description = "Compte AWS autorisé à héberger le stockage YouScout."
  type        = string
  default     = "014663857971"

  validation {
    condition     = can(regex("^[0-9]{12}$", var.aws_account_id))
    error_message = "aws_account_id doit contenir exactement douze chiffres."
  }
}

variable "aws_profile" {
  description = "Profil credential_process qui délègue à AWS CLI la session du rôle YouScoutTerraform."
  type        = string
  default     = "youscout-terraform"

  validation {
    condition     = length(trimspace(var.aws_profile)) > 0
    error_message = "Un profil AWS explicite est obligatoire."
  }
}

variable "aws_region" {
  description = "Région compatible avec la politique IAM YouScout actuelle."
  type        = string
  default     = "us-east-1"

  validation {
    condition     = var.aws_region == "us-east-1"
    error_message = "Le rôle actuel autorise uniquement us-east-1 ; adapter IAM avant de changer cette restriction."
  }
}

variable "environment" {
  description = "Environnement inclus dans le nom du bucket et les tags."
  type        = string
  default     = "dev"

  validation {
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "Utiliser dev, staging ou prod."
  }
}

variable "allowed_origins" {
  description = "Origines web exactes de Flutter, sans chemin ni wildcard. CORS ne donne aucun droit d'accès S3."
  type        = set(string)
  default     = ["http://localhost:7357", "http://127.0.0.1:7357"]

  validation {
    condition = length(var.allowed_origins) > 0 && alltrue([
      for origin in var.allowed_origins :
      contains(["http://localhost:7357", "http://127.0.0.1:7357"], origin) ||
      can(regex("^https://[a-zA-Z0-9][a-zA-Z0-9.-]*(:[0-9]{1,5})?$", origin))
    ])
    error_message = "Fournir des origines HTTPS exactes, ou les deux origines locales Flutter sur le port 7357."
  }
}

variable "abort_incomplete_upload_days" {
  description = "Délai de nettoyage des parties d'uploads multipart inachevés ; aucune vidéo terminée n'expire."
  type        = number
  default     = 1

  validation {
    condition     = var.abort_incomplete_upload_days >= 1 && floor(var.abort_incomplete_upload_days) == var.abort_incomplete_upload_days
    error_message = "Le délai de nettoyage doit être un nombre entier positif de jours."
  }
}
