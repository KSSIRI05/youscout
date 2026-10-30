package ma.youscout.video;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Contrat interne strict ; les résultats ne contiennent jamais de lien de lecture public. */
record ProcessingResult(UUID eventId, UUID videoId, UUID sourceEventId, UUID attemptId, String etag,
                        String bucket, boolean success, String videoKey, String thumbnailKey,
                        Long durationMillis, Integer width, Integer height, String error) {
    static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(65536).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    static ProcessingResult decode(String key, String raw) {
        if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > 65536) throw new IllegalArgumentException();
        var root = JSON.readValue(raw, Map.class);
        if (root == null || !root.keySet().equals(Set.of("eventId", "eventType", "eventVersion", "occurredAt", "producer", "data"))
                || !"media-worker".equals(root.get("producer")) || !Integer.valueOf(1).equals(root.get("eventVersion"))) throw new IllegalArgumentException();
        java.time.Instant.parse(text(root.get("occurredAt"), 80));
        boolean success = "VideoProcessed".equals(root.get("eventType"));
        if (!success && !"VideoProcessingFailed".equals(root.get("eventType"))) throw new IllegalArgumentException();
        var data = (Map<?, ?>) root.get("data");
        var fields = new HashSet<>(Set.of("videoId", "sourceEventId", "attemptId", "sourceETag", "s3Bucket"));
        fields.addAll(success ? Set.of("videoKey", "thumbnailKey", "durationMillis", "width", "height") : Set.of("errorCode"));
        if (data == null || !data.keySet().equals(fields)) throw new IllegalArgumentException();
        UUID video = uuid(data.get("videoId")), attempt = uuid(data.get("attemptId"));
        if (!video.toString().equals(key)) throw new IllegalArgumentException();
        String videoKey = success ? text(data.get("videoKey"), 160) : null;
        String thumbnail = success ? text(data.get("thumbnailKey"), 160) : null;
        if (success && (!videoKey.equals("processed/" + video + "/" + attempt + "/video.mp4")
                || !thumbnail.equals("processed/" + video + "/" + attempt + "/thumbnail.jpg"))) throw new IllegalArgumentException();
        Long duration = success ? positive(data.get("durationMillis"), 181000) : null;
        Integer width = success ? Math.toIntExact(positive(data.get("width"), 1280)) : null;
        Integer height = success ? Math.toIntExact(positive(data.get("height"), 720)) : null;
        String error = success ? null : text(data.get("errorCode"), 40);
        if (!success && !error.matches("[A-Z_]{1,40}")) throw new IllegalArgumentException();
        return new ProcessingResult(uuid(root.get("eventId")), video, uuid(data.get("sourceEventId")), attempt,
            text(data.get("sourceETag"), 1024), text(data.get("s3Bucket"), 63), success, videoKey, thumbnail, duration, width, height, error);
    }
    String fingerprint() {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(toString().getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(); }
    }
    static String text(Object value, int max) {
        if (!(value instanceof String s) || s.isBlank() || s.length() > max || s.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
        return (String) value;
    }
    static UUID uuid(Object value) {
        String text = text(value, 36); UUID id = UUID.fromString(text);
        if (!id.toString().equals(text)) throw new IllegalArgumentException(); return id;
    }
    static long positive(Object value, long max) {
        if (!(value instanceof Integer || value instanceof Long)) throw new IllegalArgumentException();
        long number = ((Number) value).longValue(); if (number < 1 || number > max) throw new IllegalArgumentException(); return number;
    }
}
