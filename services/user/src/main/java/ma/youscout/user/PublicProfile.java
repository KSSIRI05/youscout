package ma.youscout.user;

/** Informations volontairement publiques pour un profil communautaire. */
record PublicProfile(String subject, String displayName, String biography,
                     long followingCount, long followerCount, boolean following) {}
record FollowResult(boolean following, long followerCount) {}
record BlockResult(boolean blocked) {}
record FeedRelations(java.util.List<String> followingSubjects,
                     java.util.List<String> blockedSubjects,
                     java.util.List<String> blockedBySubjects) {
    FeedRelations {
        followingSubjects = java.util.List.copyOf(followingSubjects);
        blockedSubjects = java.util.List.copyOf(blockedSubjects);
        blockedBySubjects = java.util.List.copyOf(blockedBySubjects);
    }
}
