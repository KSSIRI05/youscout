output "video_bucket_name" {
  description = "Nom du bucket privé à fournir au futur service Video."
  value       = aws_s3_bucket.videos.bucket
}

output "video_bucket_arn" {
  description = "ARN du bucket pour les futures politiques du service Video et des workers."
  value       = aws_s3_bucket.videos.arn
}

output "aws_region" {
  description = "Région S3 à configurer dans le futur backend."
  value       = var.aws_region
}

output "allowed_origins" {
  description = "Origines autorisées par CORS ; elles ne rendent pas les objets publics."
  value       = var.allowed_origins
}
