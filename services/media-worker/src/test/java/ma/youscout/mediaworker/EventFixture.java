package ma.youscout.mediaworker;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

class EventFixture {
    static final JsonMapper mapper = new JsonMapper();
    static Map<String, Object> event(UUID eventId, UUID videoId) {
        var data = new LinkedHashMap<String, Object>();
        data.put("videoId", videoId.toString()); data.put("s3Bucket", "test-bucket");
        data.put("s3Key", "videos/" + videoId + "/source"); data.put("sourceETag", "\"opaque-etag\"");
        data.put("contentType", "video/mp4"); data.put("sizeBytes", 123);
        var root = new LinkedHashMap<String, Object>();
        root.put("eventId", eventId.toString()); root.put("eventType", "VideoUploaded"); root.put("eventVersion", 1);
        root.put("occurredAt", "2026-10-29T12:00:00Z"); root.put("producer", "video-service"); root.put("data", data);
        return root;
    }
    @SuppressWarnings("unchecked") static Map<String, Object> data(Map<String, Object> event) {
        return (Map<String, Object>) event.get("data");
    }
    static String json(Map<String, Object> event) { return mapper.writeValueAsString(event); }
}
