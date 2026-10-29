package ma.youscout.mediaworker;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class EventDecoderTest {
    final EventDecoder decoder = new EventDecoder();
    final UUID eventId = UUID.randomUUID(), videoId = UUID.randomUUID();

    @Test void acceptsContractAndPreservesOpaqueEtag() {
        var event = decoder.decode(videoId.toString(), EventFixture.json(EventFixture.event(eventId, videoId)));
        assertThat(event.eventId()).isEqualTo(eventId);
        assertThat(event.videoId()).isEqualTo(videoId);
        assertThat(event.etag()).isEqualTo("\"opaque-etag\"");
    }
    @Test void fingerprintIsIndependentOfJsonWhitespaceAndFieldOrder() {
        var fields = EventFixture.event(eventId, videoId);
        String original = EventFixture.json(fields);
        var value = fields.remove("eventId"); fields.put("eventId", value);
        assertThat(decoder.decode(videoId.toString(), original).fingerprint())
                .isEqualTo(decoder.decode(videoId.toString(), "\n " + EventFixture.json(fields)).fingerprint());
    }
    @Test void rejectsUnknownVersionExtraFieldsAndWrongProducer() {
        for (var change : List.of(java.util.Map.entry("eventVersion", (Object) 2),
                java.util.Map.entry("producer", (Object) "other-service"), java.util.Map.entry("accessToken", (Object) "not-stored"))) {
            var fields = EventFixture.event(eventId, videoId); fields.put(change.getKey(), change.getValue());
            invalid(EventFixture.json(fields));
        }
    }
    @Test void rejectsMalformedDuplicatedTrailingOrOversizedJson() {
        String valid = EventFixture.json(EventFixture.event(eventId, videoId));
        for (String body : List.of("null", "[]", "{", valid + "{}", valid.replace("\"eventVersion\":1", "\"eventVersion\":1,\"eventVersion\":1"), " ".repeat(65537))) invalid(body);
        assertThatThrownBy(() -> decoder.decode(videoId.toString(), null)).isInstanceOf(EventDecoder.InvalidEvent.class);
    }
    @Test void rejectsLocationMismatchAndControlCharacters() {
        for (var change : List.of(java.util.Map.entry("s3Key", "videos/other/source"),
                java.util.Map.entry("s3Bucket", "https://example.org"), java.util.Map.entry("sourceETag", "bad\nvalue"),
                java.util.Map.entry("contentType", "text/plain"))) {
            var fields = EventFixture.event(eventId, videoId); EventFixture.data(fields).put(change.getKey(), change.getValue());
            invalid(EventFixture.json(fields));
        }
    }
    @Test void rejectsNonIntegerAndOutOfBoundsSizes() {
        for (Object size : List.of(0, -1, 5368709121L, 1.5, "123")) {
            var fields = EventFixture.event(eventId, videoId); EventFixture.data(fields).put("sizeBytes", size);
            invalid(EventFixture.json(fields));
        }
    }
    @Test void rejectsWrongKafkaKeyAndInvalidDate() {
        String valid = EventFixture.json(EventFixture.event(eventId, videoId));
        assertThatThrownBy(() -> decoder.decode(UUID.randomUUID().toString(), valid)).isInstanceOf(EventDecoder.InvalidEvent.class);
        invalid(valid.replace("2026-10-29T12:00:00Z", "invalid-date"));
    }
    private void invalid(String value) {
        assertThatThrownBy(() -> decoder.decode(videoId.toString(), value)).isInstanceOf(EventDecoder.InvalidEvent.class);
    }
}
