package ma.youscout.video.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Construit le client sans requête AWS au démarrage et ferme ses ressources à l'arrêt. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(S3StorageProperties.class)
public class S3StorageConfiguration {
    @Bean(destroyMethod = "close")
    S3Presigner videoPresigner(S3StorageProperties properties, AwsCredentialsProvider videoCredentialsProvider) {
        return S3Presigner.builder().region(Region.of(properties.region()))
                .credentialsProvider(videoCredentialsProvider).build();
    }
    @Bean(destroyMethod = "close")
    AwsCredentialsProvider videoCredentialsProvider(S3StorageProperties properties) {
        // Le profil local explicite évite d'utiliser par inadvertance un autre profil du poste.
        if (StringUtils.hasText(properties.profile())) {
            return ProfileCredentialsProvider.builder().profileName(properties.profile()).build();
        }
        // En cloud, le SDK reçoit les identifiants temporaires de l'identité du workload.
        return DefaultCredentialsProvider.builder().build();
    }

    @Bean(destroyMethod = "close")
    S3Client videoS3Client(S3StorageProperties properties, AwsCredentialsProvider videoCredentialsProvider) {
        return S3Client.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(videoCredentialsProvider)
                // Borne l'appel S3 pendant lequel la confirmation détient son verrou PostgreSQL.
                .overrideConfiguration(config -> config.apiCallTimeout(java.time.Duration.ofSeconds(5))
                        .apiCallAttemptTimeout(java.time.Duration.ofSeconds(3)))
                .build();
    }
}
