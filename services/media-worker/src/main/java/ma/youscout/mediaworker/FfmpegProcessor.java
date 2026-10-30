package ma.youscout.mediaworker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.io.IOException;
import tools.jackson.databind.json.JsonMapper;

/** Commandes sans shell, chemins fixes, protocoles et formats bornés ; diagnostics non publiés. */
class FfmpegProcessor {
    private final ProcessingProperties properties;
    private final JsonMapper json = new JsonMapper();
    FfmpegProcessor(ProcessingProperties properties) { this.properties = properties; }
    ExecutionStore.Output process(Path directory) {
        Path source = directory.resolve("source"), video = directory.resolve("video.mp4"), thumbnail = directory.resolve("thumbnail.jpg");
        var input = probe(source, directory.resolve("input.json"));
        run(List.of(properties.ffmpeg(), "-nostdin", "-hide_banner", "-loglevel", "error", "-max_alloc", "67108864",
            "-protocol_whitelist", "file,pipe", "-format_whitelist", "mov,matroska,webm", "-i", source.toString(),
            "-map", "0:v:0", "-map", "0:a:0?", "-sn", "-dn", "-map_metadata", "-1", "-map_chapters", "-1",
            "-vf", "scale=w='min(1280,iw)':h='min(720,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2",
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-maxrate", "2M", "-bufsize", "4M",
            "-pix_fmt", "yuv420p", "-r", "30", "-c:a", "aac", "-b:a", "128k", "-threads", "2", "-filter_threads", "1",
            "-movflags", "+faststart", "-fs", Long.toString(properties.maxBytes()), "-y", video.toString()),
            directory, null, properties.transcodeSeconds(), "TRANSCODE_FAILED", true);
        var output = probe(video, directory.resolve("output.json"));
        if (Math.abs(input.durationMillis() - output.durationMillis()) > 1500) throw new ProcessingFailure("OUTPUT_TRUNCATED", false);
        run(List.of(properties.ffmpeg(), "-nostdin", "-hide_banner", "-loglevel", "error", "-protocol_whitelist", "file,pipe",
            "-i", video.toString(), "-map", "0:v:0", "-frames:v", "1", "-vf", "scale=640:-2", "-threads", "1",
            "-q:v", "2", "-update", "1", "-y", thumbnail.toString()), directory, null, 20, "THUMBNAIL_FAILED", true);
        return output;
    }

    ExecutionStore.Output probe(Path source, Path result) {
        run(List.of(properties.ffprobe(), "-v", "error", "-max_alloc", "67108864", "-protocol_whitelist", "file,pipe",
            "-format_whitelist", "mov,matroska,webm", "-show_entries",
            "format=duration,format_name:stream=codec_type,width,height:stream_disposition=attached_pic",
            "-of", "json", source.toString()), source.getParent(), result, 10, "INVALID_MEDIA", false);
        try {
            if (Files.size(result) > 65536) throw new ProcessingFailure("INVALID_MEDIA", false);
            var root = json.readValue(Files.readString(result), Map.class);
            var format = (Map<?, ?>) root.get("format");
            double seconds = Double.parseDouble(format.get("duration").toString());
            if (!Double.isFinite(seconds) || seconds <= 0 || seconds > properties.maxDurationSeconds())
                throw new ProcessingFailure("DURATION_LIMIT", false);
            var streams = (List<?>) root.get("streams");
            var video = streams.stream().map(v -> (Map<?, ?>) v).filter(v -> "video".equals(v.get("codec_type"))).findFirst()
                .orElseThrow(() -> new ProcessingFailure("NO_VIDEO_TRACK", false));
            if (video.get("disposition") instanceof Map<?, ?> flags && Integer.valueOf(1).equals(flags.get("attached_pic")))
                throw new ProcessingFailure("NO_VIDEO_TRACK", false);
            int width = ((Number) video.get("width")).intValue(), height = ((Number) video.get("height")).intValue();
            if (width < 2 || height < 2 || width > 3840 || height > 3840 || (long) width * height > 8294400)
                throw new ProcessingFailure("RESOLUTION_LIMIT", false);
            return new ExecutionStore.Output(Math.max(1, Math.round(seconds * 1000)), width, height);
        } catch (ProcessingFailure failure) { throw failure; }
        catch (IOException | RuntimeException invalid) { throw new ProcessingFailure("INVALID_MEDIA", false); }
    }

    static void run(List<String> args, Path directory, Path stdout, int seconds, String code, boolean retryable) {
        Process process = null;
        try {
            var builder = new ProcessBuilder(args).directory(directory.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().keySet().removeIf(k -> k.startsWith("AWS_") || k.endsWith("_PASSWORD") || k.endsWith("_SECRET"));
            builder.redirectOutput(stdout == null ? ProcessBuilder.Redirect.DISCARD : ProcessBuilder.Redirect.to(stdout.toFile()));
            process = builder.start();
            process.getOutputStream().close();
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) throw new ProcessingFailure("PROCESS_TIMEOUT", true);
            if (process.exitValue() != 0) throw new ProcessingFailure(code, retryable);
        } catch (IOException unavailable) { throw new ProcessingFailure("TOOLS_UNAVAILABLE", true); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new ProcessingFailure("PROCESS_INTERRUPTED", true); }
        finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                try { process.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
        }
    }
}
