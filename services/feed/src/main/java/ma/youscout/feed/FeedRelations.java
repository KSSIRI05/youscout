package ma.youscout.feed;

import java.util.List;

/** Snapshot des relations du joueur, fourni par User pour filtrer la lecture. */
record FeedRelations(List<String> followingSubjects, List<String> blockedSubjects, List<String> blockedBySubjects) {}
