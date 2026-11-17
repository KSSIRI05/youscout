package ma.youscout.mediaworker;

import java.nio.file.Path;

interface MediaStorage {
    void download(ExecutionStore.Job job, Path target);
    void upload(ExecutionStore.Job job, Path video, Path thumbnail);
    /** Deletes the given keys of one video; keys that are already gone are not an error. */
    void delete(java.util.UUID videoId, java.util.List<String> keys);
}
