package ma.youscout.mediaworker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

/** Clés générées par le domaine uniquement ; GET conditionnel et taille strictement bornée. */
class S3MediaStorage implements MediaStorage {
    private final S3Client s3;
    private final ProcessingProperties properties;
    S3MediaStorage(S3Client s3, ProcessingProperties properties) { this.s3 = s3; this.properties = properties; }
    private void validate(ExecutionStore.Job job) {
        if (!properties.bucket().equals(job.bucket()) || !job.key().equals("videos/" + job.videoId() + "/source"))
            throw new ProcessingFailure("INVALID_SOURCE", false);
        if (job.size() < 1 || job.size() > properties.maxBytes()) throw new ProcessingFailure("SIZE_LIMIT", false);
    }
    public void download(ExecutionStore.Job job, Path target) {
        validate(job);
        try {
            s3.getObject(GetObjectRequest.builder().bucket(job.bucket()).key(job.key()).ifMatch(job.etag()).build(), (response, input) -> {
                if (response.contentLength() == null || response.contentLength() != job.size())
                    throw new ProcessingFailure("SIZE_MISMATCH", false);
                try (var output = Files.newOutputStream(target)) {
                    byte[] buffer = new byte[65536]; long count = 0;
                    for (int n; (n = input.read(buffer)) != -1;) {
                        count += n;
                        if (count > job.size()) throw new ProcessingFailure("SIZE_MISMATCH", false);
                        output.write(buffer, 0, n);
                    }
                    if (count != job.size()) throw new ProcessingFailure("SIZE_MISMATCH", false);
                }
                return null;
            });
        } catch (RuntimeException error) { throw classify(error); }
    }
    public void upload(ExecutionStore.Job job, Path video, Path thumbnail) {
        validate(job);
        try {
            if (Files.size(video) < 1 || Files.size(video) > properties.maxBytes()
                    || Files.size(thumbnail) < 1 || Files.size(thumbnail) > 5 * 1024 * 1024) throw new ProcessingFailure("OUTPUT_LIMIT", false);
            s3.putObject(PutObjectRequest.builder().bucket(job.bucket()).key(job.prefix() + "video.mp4").contentType("video/mp4").build(), RequestBody.fromFile(video));
            s3.putObject(PutObjectRequest.builder().bucket(job.bucket()).key(job.prefix() + "thumbnail.jpg").contentType("image/jpeg").build(), RequestBody.fromFile(thumbnail));
        } catch (IOException error) { throw new ProcessingFailure("LOCAL_IO", true); }
        catch (RuntimeException error) { throw classify(error); }
    }
    private ProcessingFailure classify(RuntimeException error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ProcessingFailure failure) return failure;
            if (cause instanceof S3Exception s3Error) {
                if (s3Error.statusCode() == 412) return new ProcessingFailure("SOURCE_CHANGED", false);
                if (s3Error.statusCode() == 404) return new ProcessingFailure("SOURCE_MISSING", false);
            }
        }
        return new ProcessingFailure("STORAGE_UNAVAILABLE", true);
    }
}
