package ma.youscout.notification;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Firebase Cloud Messaging (API HTTP v1) avec un compte de service Google : Android, web, et iOS si APNs est
 * configuré dans le projet Firebase. Sans projet ni fichier de compte de service, l'envoi est désactivé.
 */
@Component
class PushSender {
    enum Result { SENT, INVALID_TOKEN, FAILED }
    private static final Logger log = LoggerFactory.getLogger(PushSender.class);
    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

    private final String projectId, clientEmail, tokenUri;
    private final RSAPrivateKey privateKey;
    private final RestClient http;
    private final String apiUrl;
    private volatile String accessToken;
    private volatile Instant accessUntil = Instant.EPOCH;

    PushSender(@Value("${notification.push.fcm.project-id:}") String projectId,
               @Value("${notification.push.fcm.credentials-file:}") String credentialsFile,
               @Value("${notification.push.fcm.api-url:https://fcm.googleapis.com}") String apiUrl) {
        var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(3));
        requests.setReadTimeout(Duration.ofSeconds(5));
        this.http = RestClient.builder().requestFactory(requests).build();
        this.apiUrl = apiUrl;
        if (projectId.isBlank() || credentialsFile.isBlank()) {
            this.projectId = null; this.clientEmail = null; this.tokenUri = null; this.privateKey = null;
            log.info("Push disabled: no FCM project or service-account file configured.");
            return;
        }
        try {
            Map<?, ?> account = JsonMapper.builder().build().readValue(Files.readString(Path.of(credentialsFile)), Map.class);
            String pem = ((String) account.get("private_key")).replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
            this.privateKey = (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
            this.clientEmail = (String) account.get("client_email");
            this.tokenUri = (String) account.get("token_uri");
            this.projectId = projectId;
        } catch (Exception unreadable) {
            throw new IllegalStateException("FCM service-account file is unreadable.", unreadable);
        }
    }

    boolean enabled() { return projectId != null; }

    Result send(String deviceToken, String title, String body, Map<String, String> data) {
        try {
            String bearer = accessToken();
            http.post().uri(apiUrl + "/v1/projects/{project}/messages:send", projectId).headers(h -> h.setBearerAuth(bearer))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("message", Map.of("token", deviceToken, "notification", Map.of("title", title, "body", body), "data", data)))
                .retrieve().toBodilessEntity();
            return Result.SENT;
        } catch (HttpClientErrorException.NotFound unregistered) {
            return Result.INVALID_TOKEN;
        } catch (HttpClientErrorException.BadRequest invalid) {
            String error = invalid.getResponseBodyAsString();
            return error.contains("UNREGISTERED") || error.contains("INVALID_ARGUMENT") ? Result.INVALID_TOKEN : Result.FAILED;
        } catch (HttpClientErrorException.Unauthorized expired) {
            accessToken = null;
            return Result.FAILED;
        } catch (RestClientException unavailable) {
            log.warn("FCM unavailable: {}", unavailable.getClass().getSimpleName());
            return Result.FAILED;
        }
    }

    /** Jeton OAuth du compte de service (assertion JWT signée), réutilisé jusqu'à une minute avant son expiration. */
    private String accessToken() {
        if (accessToken != null && Instant.now().isBefore(accessUntil)) return accessToken;
        try {
            Instant now = Instant.now();
            var assertion = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), new JWTClaimsSet.Builder().issuer(clientEmail)
                .audience(tokenUri).claim("scope", SCOPE).issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(3600))).build());
            assertion.sign(new RSASSASigner(privateKey));
            var form = new LinkedMultiValueMap<String, String>();
            form.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
            form.add("assertion", assertion.serialize());
            Map<?, ?> response = http.post().uri(tokenUri).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(Map.class);
            if (response == null || !(response.get("access_token") instanceof String token)) throw new RestClientException("No access token");
            long lifetime = response.get("expires_in") instanceof Number seconds ? seconds.longValue() : 0;
            accessToken = token;
            accessUntil = now.plusSeconds(Math.max(0, lifetime - 60));
            return token;
        } catch (com.nimbusds.jose.JOSEException signing) {
            throw new IllegalStateException("FCM assertion cannot be signed.", signing);
        }
    }
}
