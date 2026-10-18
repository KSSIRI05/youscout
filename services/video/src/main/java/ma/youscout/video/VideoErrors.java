package ma.youscout.video;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.TransactionException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.dao.DataAccessException;

/** Erreurs génériques sans SQL, identifiants AWS ou URL signée. */
@RestControllerAdvice
class VideoErrors extends ResponseEntityExceptionHandler {
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<ProblemDetail> databaseUnavailable(RuntimeException error) {
        return ResponseEntity.status(503).cacheControl(CacheControl.noStore())
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Stockage vidéo temporairement indisponible."));
    }
}
