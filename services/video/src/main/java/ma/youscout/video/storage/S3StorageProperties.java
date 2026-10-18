package ma.youscout.video.storage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Paramètres non secrets du stockage ; une valeur invalide bloque le démarrage. */
@Validated
@ConfigurationProperties("youscout.storage.s3")
public record S3StorageProperties(
        @NotBlank
        @Pattern(regexp = "[a-z0-9][a-z0-9-]{1,61}[a-z0-9]",
                message = "doit être un nom de bucket de 3 à 63 caractères, sans point")
        String bucket,
        @NotBlank
        @Pattern(regexp = "[a-z]{2}(-[a-z]+)+-[0-9]+", message = "doit être une région AWS")
        String region,
        String profile) {
}
