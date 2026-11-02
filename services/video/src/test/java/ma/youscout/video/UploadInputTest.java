package ma.youscout.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class UploadInputTest {
    private static final String TITLE = "Talent";

    @Test
    void acceptsUpToTenUniqueCatalogReferences() {
        var skillIds = ids(10);
        var hashtagIds = ids(10);

        UploadInput input = UploadInput.from(fields(skillIds, hashtagIds), 1024);

        assertThat(input.skillIds()).containsExactlyElementsOf(skillIds);
        assertThat(input.hashtagIds()).containsExactlyElementsOf(hashtagIds);
    }

    @Test
    void rejectsMoreThanTenSkillsOrHashtags() {
        assertBadRequest(fields(ids(11), List.of()));
        assertBadRequest(fields(List.of(), ids(11)));
    }

    @Test
    void rejectsDuplicateCatalogIds() {
        UUID duplicate = UUID.randomUUID();
        assertBadRequest(fields(List.of(duplicate, duplicate), List.of()));
        assertBadRequest(fields(List.of(), List.of(duplicate, duplicate)));
    }

    @Test
    void rejectsMalformedCatalogIdsAndNullLists() {
        Map<String, Object> malformed = baseFields();
        malformed.put("skillIds", List.of("not-a-uuid"));
        assertBadRequest(malformed);

        Map<String, Object> nullList = baseFields();
        nullList.put("hashtagIds", null);
        assertBadRequest(nullList);
    }

    private static List<UUID> ids(int count) {
        return java.util.stream.IntStream.range(0, count).mapToObj(ignored -> UUID.randomUUID()).toList();
    }

    private static Map<String, Object> fields(List<UUID> skills, List<UUID> hashtags) {
        Map<String, Object> fields = baseFields();
        fields.put("skillIds", skills.stream().map(UUID::toString).toList());
        fields.put("hashtagIds", hashtags.stream().map(UUID::toString).toList());
        return fields;
    }

    private static Map<String, Object> baseFields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("title", TITLE);
        fields.put("contentType", "video/mp4");
        fields.put("sizeBytes", 12);
        return fields;
    }

    private static void assertBadRequest(Map<String, Object> fields) {
        assertThatThrownBy(() -> UploadInput.from(fields, 1024))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(400);
    }
}
