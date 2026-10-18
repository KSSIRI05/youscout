package ma.youscout.video;

import ma.youscout.video.storage.S3StorageConfiguration;
import ma.youscout.video.storage.S3StorageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.services.s3.S3Client;

import static org.assertj.core.api.Assertions.assertThat;

/** Vérifie les vrais fichiers YAML et leurs remplacements sans session ni appel AWS. */
class S3ConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class, ValidationAutoConfiguration.class))
            .withUserConfiguration(S3StorageConfiguration.class);

    @Test
    void loadsDeployedBucketAndRegionWithoutAwsCredentials() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(S3StorageProperties.class);
            assertThat(properties.bucket()).isEqualTo("youscout-014663857971-dev-videos");
            assertThat(properties.region()).isEqualTo("us-east-1");
            assertThat(context.getBean(S3Client.class).serviceClientConfiguration().region().id())
                    .isEqualTo("us-east-1");
        });
    }

    @Test
    void localProfileSelectsDedicatedVideoCredentials() {
        runner.withPropertyValues("spring.profiles.active=local").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(S3StorageProperties.class).profile()).isEqualTo("youscout-video-sdk");
            assertThat(context.getBean("videoCredentialsProvider")).isInstanceOf(ProfileCredentialsProvider.class);
        });
    }

    @Test
    void environmentOverridesReachTheClient() {
        runner.withPropertyValues("VIDEO_S3_BUCKET=youscout-staging-videos", "VIDEO_S3_REGION=eu-west-1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(S3StorageProperties.class).bucket()).isEqualTo("youscout-staging-videos");
                    assertThat(context.getBean(S3Client.class).serviceClientConfiguration().region().id())
                            .isEqualTo("eu-west-1");
                });
    }

    @Test
    void refusesBucketUrlInsteadOfName() {
        runner.withPropertyValues("VIDEO_S3_BUCKET=https://example.com/videos").run(context ->
                assertThat(context).hasFailed());
    }

    @Test
    void refusesEmptyRegion() {
        runner.withPropertyValues("VIDEO_S3_REGION=").run(context -> assertThat(context).hasFailed());
    }
}
