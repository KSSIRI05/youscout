package ma.youscout.mediaworker;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "worker.processing.enabled", havingValue = "true")
class WorkerS3Configuration {
    @Bean(destroyMethod = "close")
    AwsCredentialsProvider workerCredentials(ProcessingProperties properties) {
        return properties.profile() == null || properties.profile().isBlank() ? DefaultCredentialsProvider.builder().build()
                : ProfileCredentialsProvider.builder().profileName(properties.profile()).build();
    }
    @Bean(destroyMethod = "close")
    S3Client workerS3(ProcessingProperties properties, AwsCredentialsProvider workerCredentials) {
        return S3Client.builder().region(Region.of(properties.region())).credentialsProvider(workerCredentials)
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(5)))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(30)).apiCallAttemptTimeout(Duration.ofSeconds(25))
                    .retryPolicy(software.amazon.awssdk.core.retry.RetryPolicy.none())).build();
    }
    @Bean MediaStorage mediaStorage(S3Client workerS3, ProcessingProperties properties) { return new S3MediaStorage(workerS3, properties); }
}
