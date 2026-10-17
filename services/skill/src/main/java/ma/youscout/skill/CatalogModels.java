package ma.youscout.skill;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
/** Compétence persistée ; active=false la masque aux lectures utilisateur sans supprimer son identifiant. */
record Skill(UUID id, String name, String description, boolean active, Instant createdAt, Instant updatedAt) {}
/** Hashtag partagé dont le nom est stocké sous sa forme canonique. */
record Hashtag(UUID id, String name, Instant createdAt) {}
/** Page de résultats et paramètres utilisés ; aucun total global n’est calculé. */
record CatalogPage<T>(List<T> items, int limit, int offset) {}
/** Indique si l’ensure a créé une ligne (201) ou retrouvé le hashtag existant (200). */
record EnsuredHashtag(Hashtag hashtag, boolean created) {}
