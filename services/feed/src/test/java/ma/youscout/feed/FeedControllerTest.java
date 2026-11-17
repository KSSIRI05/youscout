package ma.youscout.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class FeedControllerTest {
    private final FeedStore store = mock(FeedStore.class);
    private final FeedController controller = new FeedController(store);

    @Test
    void returnsTwentyEntriesAndMarksWhenAnotherPageExists() {
        var rows = java.util.stream.IntStream.range(0, 21)
            .mapToObj(index -> new FeedStore.Entry(UUID.randomUUID(), "Clip " + index, Instant.EPOCH.plusSeconds(index)))
            .toList();
        when(store.page(21, 40)).thenReturn(rows);

        var response = controller.list(40);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().items()).hasSize(20);
        assertThat(response.getBody().hasMore()).isTrue();
        verify(store).page(21, 40);
    }

    @Test
    void marksLastPageAndRejectsInvalidOffsets() {
        when(store.page(21, 10000)).thenReturn(List.of());

        assertThat(controller.list(10000).getBody().hasMore()).isFalse();
        assertThatThrownBy(() -> controller.list(-1))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("400");
        assertThatThrownBy(() -> controller.list(10001))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("400");
    }
}
