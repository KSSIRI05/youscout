package ma.youscout.video;

import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Validation stricte : le client ne choisit ni propriétaire, ni bucket, ni clé S3, ni état. */
record UploadInput(String title, String contentType, long sizeBytes) {
    static UploadInput from(Map<String, Object> fields, long maxBytes) {
        if (!fields.keySet().equals(Set.of("title", "contentType", "sizeBytes"))) {
            throw invalid("Champs attendus : title, contentType et sizeBytes uniquement.");
        }
        if (!(fields.get("title") instanceof String rawTitle) || rawTitle.isBlank()
                || rawTitle.strip().length() > 150) throw invalid("Titre requis, limité à 150 caractères.");
        // PostgreSQL refuse NUL dans ses champs texte : renvoyer 400 avant toute insertion.
        if (rawTitle.indexOf('\0') >= 0) throw invalid("Le titre contient un caractère non autorisé.");
        if (!(fields.get("contentType") instanceof String type)
                || !Set.of("video/mp4", "video/webm", "video/quicktime").contains(type)) {
            throw invalid("Type accepté : video/mp4, video/webm ou video/quicktime.");
        }
        Object size = fields.get("sizeBytes");
        if (!(size instanceof Integer || size instanceof Long)) throw invalid("sizeBytes doit être un entier.");
        long bytes = ((Number) size).longValue();
        if (bytes < 1 || bytes > maxBytes) throw invalid("Taille déclarée hors limite autorisée.");
        return new UploadInput(rawTitle.strip(), type, bytes);
    }

    static ResponseStatusException invalid(String detail) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
    }
}
