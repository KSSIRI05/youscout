package ma.youscout.video;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Validation stricte : le client ne choisit ni propriétaire, ni bucket, ni clé S3, ni état. */
record UploadInput(String title, String contentType, long sizeBytes, List<UUID> skillIds, List<UUID> hashtagIds) {
    static UploadInput from(Map<String, Object> fields, long maxBytes) {
        if (!Set.of("title", "contentType", "sizeBytes").stream().allMatch(fields::containsKey)
                || !Set.of("title", "contentType", "sizeBytes", "skillIds", "hashtagIds").containsAll(fields.keySet())) {
            throw invalid("Champs attendus : title, contentType, sizeBytes, skillIds et hashtagIds.");
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
        return new UploadInput(rawTitle.strip(), type, bytes,
                identifiers(fields, "skillIds"), identifiers(fields, "hashtagIds"));
    }

    private static List<UUID> identifiers(Map<String, Object> fields, String field) {
        if (!fields.containsKey(field)) return List.of();
        Object value = fields.get(field);
        if (value == null) throw invalid(field + " doit être une liste d'identifiants.");
        if (!(value instanceof List<?> values) || values.size() > 10) throw invalid(field + " accepte au maximum 10 identifiants.");
        var ids = new ArrayList<UUID>(values.size());
        for (Object item : values) {
            if (!(item instanceof String raw)) throw invalid(field + " doit contenir des UUID.");
            try {
                UUID id = UUID.fromString(raw);
                if (!id.toString().equals(raw)) throw invalid(field + " contient un UUID invalide.");
                ids.add(id);
            } catch (IllegalArgumentException malformed) {
                throw invalid(field + " contient un UUID invalide.");
            }
        }
        if (new HashSet<>(ids).size() != ids.size()) throw invalid(field + " ne peut pas contenir de doublons.");
        return List.copyOf(ids);
    }

    static ResponseStatusException invalid(String detail) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
    }
}
