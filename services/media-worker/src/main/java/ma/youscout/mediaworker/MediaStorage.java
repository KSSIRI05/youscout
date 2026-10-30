package ma.youscout.mediaworker;

import java.nio.file.Path;

interface MediaStorage {
    void download(ExecutionStore.Job job, Path target);
    void upload(ExecutionStore.Job job, Path video, Path thumbnail);
}
