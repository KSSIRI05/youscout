package ma.youscout.mediaworker;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/** Référence de source immuable ; aucune identité AWS ou URL temporaire. */
record VideoUploaded(UUID eventId, UUID videoId, Instant occurredAt, String bucket, String key,
                     String etag, String contentType, long sizeBytes) {
    String fingerprint() {
        // Les champs texte n'acceptent aucun caractère de contrôle : séparateurs non ambigus.
        String canonical = String.join("\n", eventId.toString(), videoId.toString(), occurredAt.toString(),
                bucket, key, etag, contentType, Long.toString(sizeBytes));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 required"); }
    }
}
