package ma.youscout.video.migration;

import java.util.logging.Logger;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Explicit opt-in entry point; disabled for normal Video service startup. */
@Component
@ConditionalOnProperty(prefix = "youscout.migration.postgres-to-mongo", name = "enabled", havingValue = "true")
public class PostgresVideoSnapshotMigrationRunner implements ApplicationRunner {
    private static final Logger LOG = Logger.getLogger(PostgresVideoSnapshotMigrationRunner.class.getName());
    private final PostgresVideoSnapshotImporter importer;

    public PostgresVideoSnapshotMigrationRunner(PostgresVideoSnapshotImporter importer) {
        this.importer = importer;
    }

    @Override
    public void run(ApplicationArguments args) {
        LOG.info(() -> "PostgreSQL to MongoDB snapshot imported: " + importer.importSnapshot());
    }
}
