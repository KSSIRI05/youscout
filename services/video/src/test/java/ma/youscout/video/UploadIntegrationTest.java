package ma.youscout.video;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.net.http.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** Vraie base, JWT signé et vrai presigner ; seuls les identifiants sont factices. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class UploadIntegrationTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-alpine");
    static final OidcFixture oidc = new OidcFixture();
    static final HttpClient client = HttpClient.newHttpClient();
    static final String INPUT = "{\"title\":\" Mon talent \",\"contentType\":\"video/mp4\",\"sizeBytes\":12}";
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean(name = "videoCredentialsProvider", answers = org.mockito.Answers.CALLS_REAL_METHODS)
    AwsCredentialsProvider credentials;
    @MockitoBean(name = "videoS3Client") S3Client s3;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", oidc::base);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> oidc.base() + "/jwks");
    }
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM videos");
        when(credentials.resolveCredentials()).thenReturn(AwsBasicCredentials.create("test-only", "test-only"));
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentLength(12L).contentType("video/mp4").eTag("\"source-etag\"").build());
    }
    @AfterAll static void cleanup() { client.close(); oidc.server.stop(0); }

    @Test void createsOwnedPendingVideoAndSignedPut() throws Exception {
        var response = post(INPUT, key(), "alice");
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        var json = JsonPath.parse(response.body());
        UUID id = UUID.fromString(json.read("$.id", String.class));
        assertThat(json.read("$.status", String.class)).isEqualTo("PENDING_UPLOAD");
        assertThat(json.read("$.title", String.class)).isEqualTo("Mon talent");
        var row = jdbc.queryForMap("SELECT * FROM videos WHERE id = ?", id);
        assertThat(row.get("owner_subject")).isEqualTo("alice");
        assertThat(row.get("s3_key")).isEqualTo("videos/" + id + "/source");
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("updated_at")).isNotNull();
        var url = URI.create(json.read("$.upload.url", String.class));
        assertThat(url.getScheme()).isEqualTo("https");
        assertThat(url.getHost()).startsWith("youscout-014663857971-dev-videos.s3.");
        String query = URLDecoder.decode(url.getRawQuery(), StandardCharsets.UTF_8);
        assertThat(query).contains("X-Amz-Expires=300", "content-length", "content-type", "X-Amz-Signature=");
        assertThat(json.read("$.upload.headers.Content-Length", String.class)).isEqualTo("12");
    }

    @Test void retriesReuseMetadataButDifferentPayloadConflicts() throws Exception {
        String key = key();
        var first = post(INPUT, key, "alice");
        var replay = post(INPUT, key, "alice");
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(JsonPath.parse(replay.body()).read("$.id", String.class))
                .isEqualTo(JsonPath.parse(first.body()).read("$.id", String.class));
        assertThat(post(INPUT.replace("12", "13"), key, "alice").statusCode()).isEqualTo(409);
        assertThat(count()).isEqualTo(1);
    }

    @Test void sameKeyIsScopedToEachVerifiedOwner() throws Exception {
        String key = key();
        assertThat(post(INPUT, key, "alice").statusCode()).isEqualTo(201);
        assertThat(post(INPUT, key, "bob").statusCode()).isEqualTo(201);
        assertThat(jdbc.queryForList("SELECT owner_subject FROM videos", String.class))
                .containsExactlyInAnyOrder("alice", "bob");
    }

    @Test void invalidPayloadsAndClientOwnershipNeverCreateMetadata() throws Exception {
        for (String body : List.of("{}", "[]", "null", INPUT.replace("12", "0"),
                INPUT.replace("12", "104857601"), INPUT.replace("12", "1.5"),
                INPUT.replace("12", "\"12\""), INPUT.replace("video/mp4", "text/plain"),
                INPUT.replace(" Mon talent ", " "), INPUT.replace(" Mon talent ", "a".repeat(151)),
                INPUT.replace("}", ",\"ownerSubject\":\"bob\"}"))) {
            assertThat(post(body, key(), "alice").statusCode()).isEqualTo(400);
        }
        assertThat(count()).isZero();
    }

    @Test void nulInTitleIsRejectedWithoutCreatingMetadata() throws Exception {
        for (String title : List.of("a\\u0000b", "\\u0000title", "title\\u0000")) {
            var response = post(INPUT.replace(" Mon talent ", title), key(), "alice");
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.headers().firstValue("Content-Type").orElse(""))
                    .startsWith("application/problem+json");
        }
        assertThat(count()).isZero();
    }

    @Test void missingIdempotencyKeyIsRejected() throws Exception {
        assertThat(post(INPUT, null, "alice").statusCode()).isEqualTo(400);
        assertThat(post(INPUT, "short", "alice").statusCode()).isEqualTo(400);
        assertThat(count()).isZero();
    }

    @Test void missingOrInsufficientIdentityCannotStartUpload() throws Exception {
        assertThat(client.send(request(INPUT, key(), null), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        assertThat(client.send(request(INPUT, key(), oidc.roleToken()), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        assertThat(count()).isZero();
    }

    @Test void signingFailureRollsBackWithoutLeakingSdkDetails() throws Exception {
        when(credentials.resolveCredentials()).thenThrow(SdkClientException.create("sensitive-provider-diagnostic"));
        var response = post(INPUT, key(), "alice");
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).doesNotContain("sensitive-provider-diagnostic", "stackTrace", "X-Amz-");
        assertThat(count()).isZero();
    }

    @Test void concurrentRetriesCreateOneRow() {
        String key = key();
        String token = oidc.profileToken("alice", null, "alice");
        var requests = java.util.stream.IntStream.range(0, 4)
                .mapToObj(i -> client.sendAsync(request(INPUT, key, token), HttpResponse.BodyHandlers.ofString())).toList();
        CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).join();
        assertThat(requests.stream().map(f -> f.join().statusCode()).toList())
                .containsExactlyInAnyOrder(201, 200, 200, 200);
        assertThat(count()).isEqualTo(1);
    }

    @Test void completesOwnedUploadAndPersistsObservedIdentity() throws Exception {
        String id = prepareId("alice");
        var response = complete(id, "alice");
        assertThat(response.statusCode()).isEqualTo(200);
        var json = JsonPath.parse(response.body());
        assertThat(json.read("$.status", String.class)).isEqualTo("UPLOADED");
        assertThat(json.read("$.completedAt", String.class)).isNotBlank();
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        var row = jdbc.queryForMap("SELECT * FROM videos WHERE id = ?", UUID.fromString(id));
        assertThat(row.get("source_etag")).isEqualTo("\"source-etag\"");
        assertThat(row.get("completed_at")).isNotNull();
        verify(s3).headObject(HeadObjectRequest.builder().bucket("youscout-014663857971-dev-videos")
                .key("videos/" + id + "/source").build());
        assertThat(response.body()).doesNotContain("source-etag", "s3_key", "owner_subject");
    }

    @Test void completionReplayIsStableAndCannotMintAnotherUploadUrl() throws Exception {
        String key = key();
        String id = JsonPath.parse(post(INPUT, key, "alice").body()).read("$.id", String.class);
        var first = complete(id, "alice");
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(SdkClientException.create("offline"));
        var second = complete(id, "alice");
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(second.body()).isEqualTo(first.body());
        verify(s3, times(1)).headObject(any(HeadObjectRequest.class));
        assertThat(post(INPUT, key, "alice").statusCode()).isEqualTo(409);
    }

    @Test void completionIsPrivateEvenForAdminAndUnknownIds() throws Exception {
        String id = prepareId("alice");
        assertThat(complete(id, "bob").statusCode()).isEqualTo(404);
        assertThat(client.send(completionRequest(id, oidc.roleToken("admin"), null),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
        assertThat(complete(UUID.randomUUID().toString(), "alice").statusCode()).isEqualTo(404);
        verify(s3, never()).headObject(any(HeadObjectRequest.class));
        assertPending(id);
    }

    @Test void absentOrInaccessibleS3ObjectRemainsPending() throws Exception {
        String id = prepareId("alice");
        for (int status : new int[]{403, 404}) {
            when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(status).build());
            assertThat(complete(id, "alice").statusCode()).isEqualTo(409);
            assertPending(id);
        }
    }

    @Test void wrongSizeOrDeclaredTypeCannotBeConfirmed() throws Exception {
        String id = prepareId("alice");
        for (var object : List.of(
                HeadObjectResponse.builder().contentLength(13L).contentType("video/mp4").eTag("etag").build(),
                HeadObjectResponse.builder().contentLength(12L).contentType("video/webm").eTag("etag").build(),
                HeadObjectResponse.builder().contentLength(12L).eTag("etag").build())) {
            when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(object);
            assertThat(complete(id, "alice").statusCode()).isEqualTo(409);
            assertPending(id);
        }
    }

    @Test void s3FailuresAndMissingEtagDoNotChangeStateOrLeakDetails() throws Exception {
        String id = prepareId("alice");
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(500).message("secret-diagnostic").build());
        var response = complete(id, "alice");
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).doesNotContain("secret-diagnostic");
        assertPending(id);
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(SdkClientException.create("secret-provider"));
        assertThat(complete(id, "alice").statusCode()).isEqualTo(503);
        assertPending(id);
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentLength(12L).contentType("video/mp4").build());
        assertThat(complete(id, "alice").statusCode()).isEqualTo(503);
        assertPending(id);
    }

    @Test void completionRequiresRoleAndRejectsClientLocationFields() throws Exception {
        String id = prepareId("alice");
        assertThat(client.send(completionRequest(id, null, null), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        assertThat(client.send(completionRequest(id, oidc.roleToken(), null), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        assertThat(complete("invalid-id", "alice").statusCode()).isEqualTo(400);
        assertThat(client.send(completionRequest(id, oidc.profileToken("alice", null, "alice"), "{\"s3Key\":\"other\"}"),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
        verify(s3, never()).headObject(any(HeadObjectRequest.class));
        assertPending(id);
    }

    @Test void concurrentConfirmationsApplyOneTransition() {
        String id;
        try { id = prepareId("alice"); } catch (Exception error) { throw new RuntimeException(error); }
        String token = oidc.profileToken("alice", null, "alice");
        var requests = java.util.stream.IntStream.range(0, 4).mapToObj(i ->
                client.sendAsync(completionRequest(id, token, null), HttpResponse.BodyHandlers.ofString())).toList();
        CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).join();
        assertThat(requests.stream().map(r -> r.join().statusCode()).toList()).containsOnly(200);
        assertThat(requests.stream().map(r -> r.join().body()).distinct().count()).isEqualTo(1);
        verify(s3, times(1)).headObject(any(HeadObjectRequest.class));
    }

    private void assertPending(String id) {
        var row = jdbc.queryForMap("SELECT status, completed_at, source_etag FROM videos WHERE id = ?", UUID.fromString(id));
        assertThat(row.get("status")).isEqualTo("PENDING_UPLOAD");
        assertThat(row.get("completed_at")).isNull();
        assertThat(row.get("source_etag")).isNull();
    }
    private String prepareId(String owner) throws Exception {
        var response = post(INPUT, key(), owner);
        assertThat(response.statusCode()).isEqualTo(201);
        return JsonPath.parse(response.body()).read("$.id", String.class);
    }
    private HttpResponse<String> complete(String id, String subject) throws Exception {
        return client.send(completionRequest(id, oidc.profileToken(subject, null, subject), null), HttpResponse.BodyHandlers.ofString());
    }
    private HttpRequest completionRequest(String id, String token, String body) {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/videos/" + id + "/complete"))
                .header("X-User-Id", "forged-owner");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        return builder.POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private int count() { return jdbc.queryForObject("SELECT count(*) FROM videos", Integer.class); }
    private String key() { return UUID.randomUUID().toString(); }
    private HttpResponse<String> post(String body, String key, String subject) throws Exception {
        return client.send(request(body, key, oidc.profileToken(subject, null, subject)), HttpResponse.BodyHandlers.ofString());
    }
    private HttpRequest request(String body, String key, String token) {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/videos/uploads"))
                .header("Content-Type", "application/json").header("X-User-Id", "forged-owner")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) builder.header("Idempotency-Key", key);
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return builder.build();
    }
}
