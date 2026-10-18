provider "aws" {
  region              = var.aws_region
  profile             = var.aws_profile
  allowed_account_ids = [var.aws_account_id]

  # Utilise le profil de rôle, jamais un secret AWS dans le code ou les tfvars.
  default_tags {
    tags = {
      Project     = "YouScout"
      Environment = var.environment
      ManagedBy   = "Terraform"
      Component   = "VideoStorage"
    }
  }
}

# Lecture seule pendant le plan ; sert aussi à refuser le profil root par erreur.
data "aws_caller_identity" "current" {}
