package ma.youscout.user;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

final class ApiSecurityErrors {
    private ApiSecurityErrors() {}
    static void unauthorized(HttpServletResponse response, AuthenticationException error) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
            error instanceof OAuth2AuthenticationException ? "Bearer error=\"invalid_token\"" : "Bearer");
        write(response, 401, "Authentification requise", "Jeton absent, invalide ou expiré.");
    }
    static void forbidden(HttpServletResponse response) throws IOException {
        write(response, 403, "Accès interdit", "Droits insuffisants pour cette ressource.");
    }
    private static void write(HttpServletResponse response, int status, String title, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"type\":\"about:blank\",\"status\":" + status
            + ",\"title\":\"" + title + "\",\"detail\":\"" + detail + "\"}");
    }
}
