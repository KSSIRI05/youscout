package ma.youscout.mediaworker;

import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class FfmpegProcessorTest {
    @TempDir Path directory;
    static ProcessingProperties properties(int maxSeconds) {
        return new ProcessingProperties(true, "test-bucket", "us-east-1", "", 104857600, maxSeconds, 30, "ffmpeg", "ffprobe");
    }
    static void clip(Path directory) {
        FfmpegProcessor.run(List.of("ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
            "testsrc2=size=320x240:rate=24", "-t", "2", "-an", "-c:v", "libx264", "-threads", "1", "-pix_fmt", "yuv420p",
            "-f", "mp4", "-y", directory.resolve("source").toString()), directory, null, 20, "TEST_CLIP_FAILED", false);
    }
    @Test void realFfmpegProducesPlayableMp4AndJpeg() throws Exception {
        clip(directory); var processor = new FfmpegProcessor(properties(180));
        var output = processor.process(directory);
        assertThat(output.durationMillis()).isBetween(1800L, 2200L);
        assertThat(output.width()).isEqualTo(320); assertThat(output.height()).isEqualTo(240);
        assertThat(Files.size(directory.resolve("video.mp4"))).isPositive();
        byte[] jpg = Files.readAllBytes(directory.resolve("thumbnail.jpg"));
        assertThat(jpg[0] & 255).isEqualTo(255); assertThat(jpg[1] & 255).isEqualTo(216);
    }
    @Test void invalidMediaIsPermanentlyRejectedBeforeTranscoding() throws Exception {
        Files.writeString(directory.resolve("source"), "not a video");
        assertThatThrownBy(() -> new FfmpegProcessor(properties(180)).process(directory))
            .isInstanceOfSatisfying(ProcessingFailure.class, e -> { assertThat(e.code).isEqualTo("INVALID_MEDIA"); assertThat(e.retryable).isFalse(); });
        assertThat(Files.exists(directory.resolve("video.mp4"))).isFalse();
    }
    @Test void durationLimitIsEnforced() {
        clip(directory);
        assertThatThrownBy(() -> new FfmpegProcessor(properties(1)).process(directory))
            .isInstanceOfSatisfying(ProcessingFailure.class, e -> assertThat(e.code).isEqualTo("DURATION_LIMIT"));
    }
    @Test void audioOnlyMediaCannotBecomeAVideo() {
        FfmpegProcessor.run(List.of("ffmpeg", "-nostdin", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=440",
            "-t", "1", "-c:a", "aac", "-f", "mp4", "-y", directory.resolve("source").toString()), directory, null, 20, "TEST_AUDIO_FAILED", false);
        assertThatThrownBy(() -> new FfmpegProcessor(properties(180)).process(directory))
            .isInstanceOfSatisfying(ProcessingFailure.class, e -> assertThat(e.code).isEqualTo("NO_VIDEO_TRACK"));
    }
    @Test void commandDeadlineKillsAnUnboundedProcess() {
        long start = System.nanoTime();
        assertThatThrownBy(() -> FfmpegProcessor.run(List.of("ffmpeg", "-nostdin", "-loglevel", "error", "-f", "lavfi", "-i",
            "testsrc2=size=64x64:rate=24", "-f", "null", "-"), directory, null, 1, "FAILED", true))
            .isInstanceOfSatisfying(ProcessingFailure.class, e -> assertThat(e.code).isEqualTo("PROCESS_TIMEOUT"));
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start).toSeconds()).isLessThan(10);
    }
}
