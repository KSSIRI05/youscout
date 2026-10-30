package ma.youscout.mediaworker;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import static org.assertj.core.api.Assertions.*;

/** Vrai SDK S3 contre un serveur HTTP isolé, sans aucun identifiant réel. */
class S3MediaStorageTest {
    @TempDir Path directory;
    HttpServer server;
    S3Client client;
    S3MediaStorage storage;
    final AtomicReference<String> match = new AtomicReference<>();
    final AtomicInteger gets = new AtomicInteger();
    int status = 200;
    final List<String> puts = new ArrayList<>();
    ExecutionStore.Job job = new ExecutionStore.Job(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, "test-bucket", "", "\"opaque-etag\"", 12);
    @BeforeEach void setup() throws Exception {
        job = new ExecutionStore.Job(job.eventId(), job.videoId(), job.attemptId(), 1, "test-bucket", "videos/" + job.videoId() + "/source", job.etag(), 12);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (exchange.getRequestMethod().equals("GET")) {
                gets.incrementAndGet(); match.set(exchange.getRequestHeaders().getFirst("If-Match"));
                byte[] bytes = status == 200 ? "source-bytes".getBytes() : "<Error><Code>PreconditionFailed</Code></Error>".getBytes();
                exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
            } else {
                puts.add(exchange.getRequestURI().getPath() + "|" + exchange.getRequestHeaders().getFirst("Content-Type"));
                exchange.getRequestBody().readAllBytes(); exchange.getResponseHeaders().add("ETag", "\"output-etag\""); exchange.sendResponseHeaders(200, -1);
            }
            exchange.close();
        });
        server.start();
        client = S3Client.builder().region(Region.US_EAST_1).forcePathStyle(true)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("fake-test-key", "fake-test-secret")))
            .endpointOverride(URI.create("http://127.0.0.1:" + server.getAddress().getPort())).build();
        storage = new S3MediaStorage(client, FfmpegProcessorTest.properties(180));
    }
    @AfterEach void close() { client.close(); server.stop(0); }
    @Test void conditionalGetUsesExactEtagAndWritesExpectedBytes() throws Exception {
        var path = directory.resolve("source"); storage.download(job, path);
        assertThat(match.get()).isEqualTo(job.etag()); assertThat(Files.readString(path)).isEqualTo("source-bytes");
    }
    @Test void changedSourceIsPermanentFailure() {
        status = 412;
        assertThatThrownBy(() -> storage.download(job, directory.resolve("source")))
            .isInstanceOfSatisfying(ProcessingFailure.class, e -> { assertThat(e.code).isEqualTo("SOURCE_CHANGED"); assertThat(e.retryable).isFalse(); });
    }
    @Test void wrongBucketOrSizeNeverMakesARequest() {
        var wrong = new ExecutionStore.Job(job.eventId(), job.videoId(), job.attemptId(), 1, "other-bucket", job.key(), job.etag(), 12);
        assertThatThrownBy(() -> storage.download(wrong, directory.resolve("source"))).isInstanceOf(ProcessingFailure.class);
        assertThat(gets.get()).isZero();
        var size = new ExecutionStore.Job(job.eventId(), job.videoId(), job.attemptId(), 1, job.bucket(), job.key(), job.etag(), 13);
        assertThatThrownBy(() -> storage.download(size, directory.resolve("source")))
            .isInstanceOfSatisfying(ProcessingFailure.class, e -> assertThat(e.code).isEqualTo("SIZE_MISMATCH"));
    }
    @Test void outputsUseFencedPrefixAndCorrectContentTypes() throws Exception {
        var video = Files.writeString(directory.resolve("video.mp4"), "mp4");
        var thumb = Files.writeString(directory.resolve("thumbnail.jpg"), "jpeg");
        storage.upload(job, video, thumb);
        assertThat(puts).containsExactly("/test-bucket/" + job.prefix() + "video.mp4|video/mp4", "/test-bucket/" + job.prefix() + "thumbnail.jpg|image/jpeg");
    }
}
