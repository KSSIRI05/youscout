package ma.youscout.user;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.WebRequest;

/** Traduit les erreurs de profil et de lecture JSON en réponses application/problem+json. */
@RestControllerAdvice(assignableTypes = ProfileController.class)
class ProfileExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(InvalidProfileException.class)
    ProblemDetail invalidProfile(InvalidProfileException error) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, error.getMessage());
        problem.setTitle("Profil invalide");
        return problem;
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Le corps doit être un objet JSON valide.");
        problem.setTitle("Requête invalide");
        return handleExceptionInternal(exception, problem, headers, status, request);
    }
}
