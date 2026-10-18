# Tous les runs sont des plans avec un fournisseur simulé : aucun accès AWS requis.
mock_provider "aws" {
  override_during = plan

  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "014663857971"
      arn        = "arn:aws:sts::014663857971:assumed-role/YouScoutTerraform/test"
    }
  }

  mock_resource "aws_s3_bucket" {
    defaults = {
      arn = "arn:aws:s3:::youscout-014663857971-dev-videos"
      id  = "youscout-014663857971-dev-videos"
    }
  }
}

run "private_storage_defaults" {
  command = plan

  assert {
    condition = (
      aws_s3_bucket.videos.bucket == "youscout-014663857971-dev-videos" &&
      aws_s3_bucket.videos.force_destroy == false &&
      aws_s3_bucket_public_access_block.videos.block_public_acls &&
      aws_s3_bucket_public_access_block.videos.ignore_public_acls &&
      aws_s3_bucket_public_access_block.videos.block_public_policy &&
      aws_s3_bucket_public_access_block.videos.restrict_public_buckets &&
      one(aws_s3_bucket_ownership_controls.videos.rule).object_ownership == "BucketOwnerEnforced" &&
      one(aws_s3_bucket_server_side_encryption_configuration.videos.rule).apply_server_side_encryption_by_default[0].sse_algorithm == "AES256"
    )
    error_message = "Le stockage doit rester privé, chiffré et sans suppression forcée des objets."
  }

  assert {
    condition = (
      jsondecode(aws_s3_bucket_policy.https_only.policy).Statement[0].Effect == "Deny" &&
      jsondecode(aws_s3_bucket_policy.https_only.policy).Statement[0].Condition.Bool["aws:SecureTransport"] == "false" &&
      jsondecode(aws_s3_bucket_policy.https_only.policy).Statement[0].Condition.Bool["aws:PrincipalIsAWSService"] == "false" &&
      toset(jsondecode(aws_s3_bucket_policy.https_only.policy).Statement[0].Resource) == toset([
        "arn:aws:s3:::youscout-014663857971-dev-videos",
        "arn:aws:s3:::youscout-014663857971-dev-videos/*"
      ])
    )
    error_message = "La politique doit refuser HTTP pour le bucket et ses objets sans accorder d'accès."
  }

  assert {
    condition = (
      one(aws_s3_bucket_cors_configuration.videos.cors_rule).allowed_origins == toset(["http://localhost:7357", "http://127.0.0.1:7357"]) &&
      one(aws_s3_bucket_cors_configuration.videos.cors_rule).allowed_methods == toset(["GET", "HEAD", "PUT"]) &&
      length(aws_s3_bucket_lifecycle_configuration.videos.rule) == 1 &&
      one(aws_s3_bucket_lifecycle_configuration.videos.rule).abort_incomplete_multipart_upload[0].days_after_initiation == 1 &&
      length(one(aws_s3_bucket_lifecycle_configuration.videos.rule).expiration) == 0
    )
    error_message = "Limiter CORS à Flutter et le nettoyage aux multipart inachevés, sans expiration des vidéos."
  }
}

run "reject_wildcard_origin" {
  command = plan
  variables {
    allowed_origins = ["*"]
  }
  expect_failures = [var.allowed_origins]
}

run "reject_nonlocal_http_origin" {
  command = plan
  variables {
    allowed_origins = ["http://example.org"]
  }
  expect_failures = [var.allowed_origins]
}

run "reject_other_region" {
  command = plan
  variables {
    aws_region = "eu-west-1"
  }
  expect_failures = [var.aws_region]
}

run "reject_fractional_cleanup_delay" {
  command = plan
  variables {
    abort_incomplete_upload_days = 0.5
  }
  expect_failures = [var.abort_incomplete_upload_days]
}

run "reject_root_identity" {
  command = plan
  override_data {
    target = data.aws_caller_identity.current
    values = {
      account_id = "014663857971"
      arn        = "arn:aws:iam::014663857971:root"
    }
  }
  expect_failures = [aws_s3_bucket.videos]
}

run "reject_other_account" {
  command = plan
  override_data {
    target = data.aws_caller_identity.current
    values = {
      account_id = "123456789012"
      arn        = "arn:aws:sts::123456789012:assumed-role/YouScoutTerraform/test"
    }
  }
  expect_failures = [aws_s3_bucket.videos]
}
