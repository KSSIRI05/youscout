locals {
  # Le préfixe respecte exactement le périmètre du rôle IAM déjà créé.
  bucket_name = "youscout-${var.aws_account_id}-${var.environment}-videos"
}

resource "aws_s3_bucket" "videos" {
  bucket        = local.bucket_name
  force_destroy = false

  lifecycle {
    # Empêche un plan de remplacer/détruire le bucket tant que ce bloc est présent.
    prevent_destroy = true

    precondition {
      condition = data.aws_caller_identity.current.account_id == var.aws_account_id && startswith(
        data.aws_caller_identity.current.arn,
        "arn:aws:sts::${var.aws_account_id}:assumed-role/YouScoutTerraform/"
      )
      error_message = "Utiliser le rôle YouScoutTerraform du compte attendu, via le profil youscout ; le profil root n'est pas accepté."
    }
  }
}

resource "aws_s3_bucket_public_access_block" "videos" {
  bucket                  = aws_s3_bucket.videos.id
  block_public_acls       = true
  ignore_public_acls      = true
  block_public_policy     = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "videos" {
  bucket = aws_s3_bucket.videos.id

  rule {
    # Pas d'ACL par objet ; le futur upload ne doit pas envoyer public-read.
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "videos" {
  bucket = aws_s3_bucket.videos.id

  rule {
    apply_server_side_encryption_by_default {
      # SSE-S3 chiffre les nouveaux objets sans créer de clé KMS à administrer.
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_cors_configuration" "videos" {
  bucket = aws_s3_bucket.videos.id

  cors_rule {
    id              = "flutter-presigned-access"
    allowed_origins = sort(tolist(var.allowed_origins))
    allowed_methods = ["GET", "HEAD", "PUT"]
    allowed_headers = ["content-type", "content-md5", "range", "x-amz-*"]
    expose_headers  = ["ETag", "x-amz-request-id"]
    max_age_seconds = 600
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "videos" {
  bucket = aws_s3_bucket.videos.id

  rule {
    id     = "abort-incomplete-multipart-uploads"
    status = "Enabled"

    # Filtre sur tout le bucket, sans filtre par tag incompatible avec cet abandon.
    filter {}

    abort_incomplete_multipart_upload {
      days_after_initiation = var.abort_incomplete_upload_days
    }
  }
}

resource "aws_s3_bucket_policy" "https_only" {
  bucket = aws_s3_bucket.videos.id

  # Ce Deny n'accorde aucun accès : les autorisations restent dans IAM.
  # L'exclusion des services AWS évite de bloquer leurs appels dont le contexte TLS est masqué.
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "DenyInsecureTransport"
      Effect    = "Deny"
      Principal = "*"
      Action    = "s3:*"
      Resource  = [aws_s3_bucket.videos.arn, "${aws_s3_bucket.videos.arn}/*"]
      Condition = {
        Bool = {
          "aws:SecureTransport"       = "false"
          "aws:PrincipalIsAWSService" = "false"
        }
      }
    }]
  })

  depends_on = [aws_s3_bucket_public_access_block.videos]
}
