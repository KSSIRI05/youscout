package ma.youscout.video;

import java.time.Instant;
import java.util.UUID;

/** Métadonnées appartenant au domaine Video ; aucun fichier vidéo ni URL présignée en base. */
record VideoUpload(UUID id, String ownerSubject, String title, String bucket, String key,
                   String contentType, long sizeBytes, String status, Instant createdAt, Instant updatedAt,
                   Instant completedAt, String sourceEtag) {}
