package ma.youscout.user;

import java.util.Map;
import java.util.Set;

/** Un champ absent reste inchangé ; null explicite, champs inconnus et conversions de types sont refusés. */
record ProfilePatch(String displayName, String biography) {
    static ProfilePatch from(Map<String, Object> fields) {
        if (fields == null || fields.isEmpty() || !Set.of("displayName", "biography").containsAll(fields.keySet())) {
            throw new InvalidProfileException("Fournir displayName et/ou biography, sans autre champ.");
        }
        // null interne signifie « absent du PATCH » ; il ne représente pas une demande d’effacement.
        String name = null;
        String biography = null;
        if (fields.containsKey("displayName")) {
            name = text(fields.get("displayName"), "displayName").strip();
            if (name.isBlank() || length(name) > 80) {
                throw new InvalidProfileException("displayName doit contenir entre 1 et 80 caractères après suppression des espaces aux extrémités.");
            }
        }
        if (fields.containsKey("biography")) {
            biography = text(fields.get("biography"), "biography");
            if (length(biography) > 500) {
                throw new InvalidProfileException("biography ne doit pas dépasser 500 caractères.");
            }
        }
        return new ProfilePatch(name, biography);
    }

    private static String text(Object value, String field) {
        if (!(value instanceof String text) || text.indexOf('\0') >= 0) {
            throw new InvalidProfileException(field + " doit être une chaîne non nulle sans caractère nul.");
        }
        return text;
    }

    // Compte les points de code Unicode, pour ne pas compter un emoji comme deux caractères Java.
    private static int length(String value) { return value.codePointCount(0, value.length()); }
}
