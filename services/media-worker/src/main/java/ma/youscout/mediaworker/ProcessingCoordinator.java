package ma.youscout.mediaworker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@ConditionalOnProperty(name = "worker.processing.enabled", havingValue = "true")
class ProcessingCoordinator {
    private static final Logger log = LoggerFactory.getLogger(ProcessingCoordinator.class);
    private final ExecutionStore store;
    private final MediaStorage storage;
    private final FfmpegProcessor processor;
    ProcessingCoordinator(ExecutionStore store, MediaStorage storage, ProcessingProperties properties) {
        this.store = store; this.storage = storage; this.processor = new FfmpegProcessor(properties);
    }
    @Scheduled(fixedDelay = 1000, initialDelay = 5000)
    void poll() {
        try { runOne(); }
        catch (RuntimeException unavailable) { log.warn("Processing state unavailable; leases preserve recoverability."); }
    }
    boolean runOne() {
        var candidate = store.claim();
        if (candidate.isEmpty()) return false;
        var job = candidate.get();
        if (job.attempts() > 3) { store.finish(job, null, new ProcessingFailure("RETRY_EXHAUSTED", false)); return true; }
        Path directory = null;
        ExecutionStore.Output output = null;
        ProcessingFailure failure = null;
        try {
            directory = Files.createTempDirectory("youscout-media-" + job.attemptId() + "-").toAbsolutePath();
            storage.download(job, directory.resolve("source"));
            output = processor.process(directory);
            storage.upload(job, directory.resolve("video.mp4"), directory.resolve("thumbnail.jpg"));
        } catch (ProcessingFailure error) { output = null; failure = error; }
        catch (Exception unavailable) { output = null; failure = new ProcessingFailure("PROCESSING_UNAVAILABLE", true); }
        finally {
            if (directory != null) {
                // Seul le dossier créé par cette tentative est parcouru, sans suivre les liens symboliques.
                try (var files = Files.walk(directory)) {
                    for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
                } catch (java.io.IOException ignored) { log.warn("Temporary media directory cleanup deferred."); }
            }
        }
        store.finish(job, output, failure);
        return true;
    }
}
