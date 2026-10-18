terraform {
  required_version = ">= 1.10.0, < 2.0.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # État local pour ce premier socle ; aucun bucket backend n'est supposé exister.
  # Les fichiers d'état et de plan sont privés et ignorés par Git.
  backend "local" {}
}
