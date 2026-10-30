package ma.youscout.mediaworker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Premier socle : réception durable des tâches, sans téléchargement ni exécution FFmpeg. */
@SpringBootApplication
@EnableConfigurationProperties({IngestionProperties.class, ProcessingProperties.class})
public class MediaWorkerApplication {
    public static void main(String[] args) { SpringApplication.run(MediaWorkerApplication.class, args); }
}
