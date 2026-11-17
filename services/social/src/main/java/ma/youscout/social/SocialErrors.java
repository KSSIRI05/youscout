package ma.youscout.social;

import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Répond directement en problem+json : sans ce gestionnaire, Spring renvoie l'erreur vers /error,
 * refusé par la configuration de sécurité, et chaque 400/404/503 devenait un 403.
 */
@RestControllerAdvice
class SocialErrors extends ResponseEntityExceptionHandler {
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> databaseUnavailable(DataAccessException error) {
        return ResponseEntity.status(503).cacheControl(CacheControl.noStore())
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Interactions temporairement indisponibles."));
    }
}
