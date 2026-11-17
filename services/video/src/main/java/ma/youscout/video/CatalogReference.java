package ma.youscout.video;

import java.util.UUID;

/** Instantané public d'une entrée active du catalogue Skill/Hashtag. */
record CatalogReference(UUID id, String name) {}
