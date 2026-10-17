package ma.youscout.skill;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

final class CatalogInput {
    private CatalogInput() {}
    static void fields(Map<String, Object> body, Set<String> allowed) {
        if (body == null || body.isEmpty() || !allowed.containsAll(body.keySet())) bad("Champs absents ou inconnus.");
    }
    static String text(Object value, String field, int max, boolean blankAllowed) {
        if (!(value instanceof String)) bad(field + " doit être une chaîne non nulle.");
        String text = (String) value;
        if (text.indexOf('\0') >= 0 || text.codePointCount(0, text.length()) > max || (!blankAllowed && text.isBlank())) {
            bad(field + " est vide, trop long ou contient un caractère nul.");
        }
        return text;
    }
    static String name(Object value) {
        if (!(value instanceof String)) bad("name doit être une chaîne non nulle.");
        String name = Normalizer.normalize((String) value, Normalizer.Form.NFKC).strip().replaceAll("(?U)\\s+", " ");
        return text(name, "name", 80, false);
    }
    static String key(String name) { return Normalizer.normalize(name, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT); }
    static String hashtag(Object value) {
        if (!(value instanceof String)) bad("name doit être une chaîne non nulle.");
        String name = key((String) value).strip();
        if (name.startsWith("#")) name = name.substring(1);
        text(name, "name", 50, false);
        if (!name.matches("[\\p{L}\\p{M}\\p{N}_]+")) bad("Un hashtag contient uniquement lettres, nombres, marques Unicode et underscore.");
        return name;
    }
    static String search(String query) {
        text(query, "q", 80, true);
        String result = key(query).strip().replaceAll("(?U)\\s+", " ");
        return result.startsWith("#") ? result.substring(1) : result;
    }
    static void page(int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0 || offset > 10000) bad("limit doit être compris entre 1 et 100 ; offset entre 0 et 10000.");
    }
    static String subject(Jwt jwt) {
        String value = jwt.getSubject();
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > 255 || value.indexOf('\0') >= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Identité du jeton invalide.");
        }
        return value;
    }
    static void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
