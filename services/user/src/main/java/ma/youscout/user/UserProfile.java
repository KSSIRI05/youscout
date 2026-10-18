package ma.youscout.user;

import java.time.Instant;

/** Vue immuable du profil persisté ; subject désigne son propriétaire dans Keycloak. */
record UserProfile(String subject, String displayName, String biography,
                   Instant createdAt, Instant updatedAt) {}
