package ma.youscout.user;

import java.time.Instant;

record UserProfile(String subject, String displayName, String biography,
                   Instant createdAt, Instant updatedAt) {}
