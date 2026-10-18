package ma.youscout.skill;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Convertit les conflits d’unicité et les corps JSON invalides en erreurs HTTP structurées. */
@RestControllerAdvice
class CatalogErrors extends ResponseEntityExceptionHandler {
    @ExceptionHandler(DuplicateKeyException.class)
    ProblemDetail duplicate(DuplicateKeyException error) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,"Une compétence porte déjà ce nom canonique.");
    }
    @Override protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException error,
            HttpHeaders headers,HttpStatusCode status,WebRequest request) {
        return handleExceptionInternal(error,ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,"Objet JSON invalide."),headers,status,request);
    }
}
