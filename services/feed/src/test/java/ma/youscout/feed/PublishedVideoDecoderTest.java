package ma.youscout.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PublishedVideoDecoderTest {
    private final PublishedVideoDecoder decoder = new PublishedVideoDecoder();
    private final UUID videoId = UUID.randomUUID();
    private final UUID skillId = UUID.randomUUID();
    private final UUID hashtagId = UUID.randomUUID();
    private final String event = """
            {
              "eventId":"%s",
              "eventType":"VideoPublished",
              "eventVersion":2,
              "occurredAt":"2026-01-01T12:00:00Z",
              "producer":"video-service",
              "data":{
                "videoId":"%s",
                "ownerSubject":"player-1",
                "title":"A match",
                "publishedAt":"2026-01-01T12:00:00Z",
                "skills":[{"id":"%s","name":"Goalkeeping"}],
                "hashtags":[{"id":"%s","name":"#football"}]
              }
            }
            """;

    @Test
    void decodesCanonicalSkillAndHashtagReferences() {
        PublishedVideo video = decoder.decode(videoId.toString(), payload());

        assertThat(video.skills()).containsExactly(new FeedTag(skillId, "Goalkeeping"));
        assertThat(video.hashtags()).containsExactly(new FeedTag(hashtagId, "#football"));
    }

    @Test
    void rejectsDuplicateReferencesAndUnknownCatalogIdsAreNotAcceptedAsTags() {
        String duplicateSkills = payload().replace(
                "\"skills\":[{\"id\":\"" + skillId + "\",\"name\":\"Goalkeeping\"}]",
                "\"skills\":[{\"id\":\"" + skillId + "\",\"name\":\"Goalkeeping\"},{\"id\":\"" + skillId + "\",\"name\":\"Goalkeeping\"}]");
        assertInvalid(duplicateSkills);

        String malformedId = payload().replace(skillId.toString(), "not-a-uuid");
        assertInvalid(malformedId);
    }

    @Test
    void rejectsMoreThanTenSelectedSkills() {
        StringBuilder manySkills = new StringBuilder("\"skills\":[");
        for (int index = 0; index < 11; index++) {
            if (index > 0) manySkills.append(',');
            manySkills.append("{\"id\":\"").append(UUID.randomUUID())
                    .append("\",\"name\":\"Skill ").append(index).append("\"}");
        }
        manySkills.append(']');
        String tooMany = payload().replace("\"skills\":[{\"id\":\"" + skillId + "\",\"name\":\"Goalkeeping\"}]", manySkills.toString());
        assertInvalid(tooMany);
    }

    private String payload() {
        return event.formatted(UUID.randomUUID(), videoId, skillId, hashtagId);
    }

    private void assertInvalid(String payload) {
        assertThatThrownBy(() -> decoder.decode(videoId.toString(), payload))
                .isInstanceOf(PublishedVideoDecoder.InvalidEvent.class);
    }
}
