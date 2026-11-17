package ma.youscout.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class CatalogReferenceClientTest {
    private MockRestServiceServer server;
    private CatalogReferenceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://skill.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new CatalogReferenceClient(builder.build());
    }

    @Test
    void returnsCanonicalSkillNameAndForwardsPlayerToken() {
        UUID id = UUID.randomUUID();
        server.expect(requestTo("http://skill.test/v1/skills/" + id))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer player-token"))
                .andRespond(withSuccess("""
                        {"id":"%s","name":"Goalkeeping","description":"Active skill","active":true}
                        """.formatted(id), MediaType.APPLICATION_JSON));

        assertThat(client.skills(List.of(id), "player-token"))
                .containsExactly(new CatalogReference(id, "Goalkeeping"));
        server.verify();
    }

    @Test
    void rejectsAnUnknownHashtagSelectedFromAStaleCatalogue() {
        UUID id = UUID.randomUUID();
        server.expect(requestTo("http://skill.test/v1/hashtags/" + id))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.hashtags(List.of(id), "player-token"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(400));
        server.verify();
    }
}
