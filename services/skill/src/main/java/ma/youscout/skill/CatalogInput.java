package ma.youscout.skill;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

/** Centralise les limites, la normalisation Unicode et la validation des identités du catalogue. */
final class CatalogInput {
    private CatalogInput() {}
    // Une liste de champs autorisés empêche les modifications de propriétés internes.
    static void fields(Map<String, Object> body, Set<String> allowed) {
        if (body == null || body.isEmpty() || !allowed.containsAll(body.keySet())) bad("Champs absents ou inconnus.");
    }
    // Les limites portent sur les points de code ; U+0000 n’est pas accepté par PostgreSQL.
    static String text(Object value, String field, int max, boolean blankAllowed) {
        if (!(value instanceof String)) bad(field + " doit être une chaîne non nulle.");
        String text = (String) value;
        if (text.indexOf('\0') >= 0 || text.codePointCount(0, text.length()) > max || (!blankAllowed && text.isBlank())) {
            bad(field + " est vide, trop long ou contient un caractère nul.");
        }
        return text;
    }
    // NFKC rapproche les écritures compatibles ; les espaces successifs deviennent un espace.
    static String name(Object value) {
        if (!(value instanceof String)) bad("name doit être une chaîne non nulle.");
        String name = Normalizer.normalize((String) value, Normalizer.Form.NFKC).strip().replaceAll("(?U)\\s+", " ");
        return text(name, "name", 80, false);
    }
    // La casse est indépendante de la langue de la machine, pour une unicité stable.
    static String key(String name) { return Normalizer.normalize(name, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT); }
    // Le # est décoratif : #Football et football désignent le même hashtag.
    static String hashtag(Object value) {
        if (!(value instanceof String)) bad("name doit être une chaîne non nulle.");
        String name = key((String) value).strip();
        if (name.startsWith("#")) name = name.substring(1);
        text(name, "name", 50, false);
        if (!name.matches("[\\p{L}\\p{M}\\p{N}_]+")) bad("Un hashtag contient uniquement lettres, nombres, marques Unicode et underscore.");
        return name;
    }
    // Normalise la recherche ; le repository effectue ensuite une recherche littérale.
    static String search(String query) {
        text(query, "q", 80, true);
        String result = key(query).strip().replaceAll("(?U)\\s+", " ");
        return result.startsWith("#") ? result.substring(1) : result;
    }
    // Borne les tailles de page et la profondeur des requêtes acceptées.
    static void page(int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0 || offset > 10000) bad("limit doit être compris entre 1 et 100 ; offset entre 0 et 10000.");
    }
    // Rejette une identité absente ou incompatible avec le stockage avant toute écriture personnelle.
    static String subject(Jwt jwt) {
        String value = jwt.getSubject();
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > 255 || value.indexOf('\0') >= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Identité du jeton invalide.");
        }
        return value;
    }
    static void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
