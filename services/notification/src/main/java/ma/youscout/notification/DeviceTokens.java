package ma.youscout.notification;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Les jetons push sont stockés chiffrés (AES-256-GCM) et retrouvés par une empreinte HMAC : une copie de la base
 * ne livre ni les jetons ni un moyen de les vérifier. La clé est dérivée du secret notification.device-key.
 */
@Component
class DeviceTokens {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;

    DeviceTokens(@Value("${notification.device-key:}") String secret) {
        this.key = secret.isBlank() ? null : new SecretKeySpec(sha256(secret), "AES");
    }

    String hash(String token) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(require().getEncoded(), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(token.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    String encrypt(String token) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, require(), new GCMParameterSpec(128, iv));
            byte[] sealed = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(sealed, 0, out, iv.length, sealed.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    String decrypt(String stored) {
        try {
            byte[] in = Base64.getDecoder().decode(stored);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, require(), new GCMParameterSpec(128, in, 0, 12));
            return new String(cipher.doFinal(in, 12, in.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException tampered) {
            throw new IllegalStateException("Device token cannot be decrypted.", tampered);
        }
    }

    private SecretKeySpec require() {
        if (key == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "L'enregistrement des appareils n'est pas configuré.");
        return key;
    }

    private static byte[] sha256(String secret) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
