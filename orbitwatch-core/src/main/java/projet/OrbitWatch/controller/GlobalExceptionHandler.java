package projet.OrbitWatch.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;
import java.util.Map;

/**
 * Gestionnaire global d'exceptions pour tous les contrôleurs REST.
 *
 * En étendant {@link ResponseEntityExceptionHandler}, toutes les exceptions
 * Spring MVC standard (400 MissingParam, 405 MethodNotAllowed, etc.) sont
 * automatiquement gérées par la classe parente avec le bon code HTTP.
 *
 * Ce handler ne traite que les exceptions techniques non anticipées → 500.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) throws Exception {

        // Exceptions annotées @ResponseStatus (TleNotFoundException → 404, AmbiguousTleException → 409)
        if (AnnotationUtils.findAnnotation(ex.getClass(), ResponseStatus.class) != null) {
            throw ex;
        }

        // ResponseStatusException gérée par Spring MVC
        if (ex instanceof ResponseStatusException) {
            throw ex;
        }

        log.error("[GlobalExceptionHandler] Erreur technique inattendue : {}", ex.getMessage(), ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                        "status",    500,
                        "error",     "Internal Server Error",
                        "message",   ex.getMessage() != null ? ex.getMessage() : "Erreur inattendue",
                        "timestamp", Instant.now().toString()
                ));
    }
}


