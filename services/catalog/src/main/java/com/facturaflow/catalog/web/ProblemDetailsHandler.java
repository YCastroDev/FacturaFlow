package com.facturaflow.catalog.web;

import com.facturaflow.catalog.model.CatalogRuleException;
import com.facturaflow.catalog.service.ConcurrentUpdateException;
import com.facturaflow.catalog.service.CustomerNotFoundException;
import com.facturaflow.catalog.service.DuplicateCustomerDocumentException;
import com.facturaflow.catalog.service.DuplicateProductCodeException;
import com.facturaflow.catalog.service.ProductNotFoundException;
import com.facturaflow.catalog.service.StaleVersionException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * Traduce excepciones a Problem Details (RFC 9457), con el mismo formato que invoicing: {@code type}
 * estable {@code urn:facturaflow:problem:<slug>}, sin trazas ni mensajes de Spring, Jackson o
 * Hibernate. Solo se devuelven como {@code detail} los mensajes escritos para personas (los del
 * modelo y los de las excepciones del servicio). Tabla completa en el ADR 0011.
 */
@RestControllerAdvice
class ProblemDetailsHandler extends ResponseEntityExceptionHandler {

    static final String TYPE_PREFIX = "urn:facturaflow:problem:";

    private static final Logger LOG = LoggerFactory.getLogger(ProblemDetailsHandler.class);

    // ----- 400: forma de la petición -----

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error(error.getField(), error.getDefaultMessage()))
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "invalid-request", "Petición inválida",
                "La petición tiene campos faltantes o con formato inválido.");
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    /** Parámetros de consulta fuera de rango ({@code page}, {@code size}, {@code q}). */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        List<Map<String, String>> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> error(result.getMethodParameter().getParameterName(), message(error))))
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "invalid-parameter", "Parámetro inválido",
                "Algún parámetro está fuera de rango.");
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    /** JSON mal formado, tipos incorrectos, enums desconocidos o campos que no existen. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "malformed-request", "Cuerpo ilegible",
                "El cuerpo no es JSON válido, o algún campo no existe o tiene un tipo o valor no admitido.");
        JacksonException jackson = findCause(ex, JacksonException.class);
        if (jackson != null) {
            String field = jsonPath(jackson);
            if (field != null) {
                problem.setProperty("field", field);   // solo la ruta del campo, nunca el mensaje de Jackson
            }
            if (jackson instanceof UnrecognizedPropertyException) {
                problem.setDetail("La petición contiene un campo que no existe en la API.");
            }
        }
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    /** Parámetro con tipo incorrecto, p. ej. un id que no es UUID o un tipo de documento desconocido. */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
                                                        HttpStatusCode status, WebRequest request) {
        String name = ex instanceof MethodArgumentTypeMismatchException mismatch ? mismatch.getName()
                : ex.getPropertyName();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "invalid-parameter", "Parámetro inválido",
                "El parámetro '" + name + "' no tiene un formato válido.");
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(MissingServletRequestParameterException ex,
                                                                          HttpHeaders headers, HttpStatusCode status,
                                                                          WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "missing-parameter", "Falta un parámetro",
                "El parámetro '" + ex.getParameterName() + "' es obligatorio.");
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    // ----- Concurrencia: If-Match (ADR 0011) -----

    @ExceptionHandler(InvalidIfMatchException.class)
    ProblemDetail invalidIfMatch(InvalidIfMatchException ex) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-if-match", "If-Match inválido",
                "If-Match debe ser un único ETag de este servicio, tal como llegó en el header ETag (p. ej. \"3\"). "
                        + "No se admiten '*', listas ni ETags débiles (W/).");
    }

    @ExceptionHandler(PreconditionRequiredException.class)
    ProblemDetail preconditionRequired(PreconditionRequiredException ex) {
        return problem(HttpStatus.PRECONDITION_REQUIRED, "precondition-required", "Falta If-Match",
                "Para actualizar envíe el header If-Match con el ETag de su última consulta (p. ej. If-Match: \"0\"). "
                        + "Lo encuentra en el header ETag de GET /{id}.");
    }

    @ExceptionHandler(StaleVersionException.class)
    ProblemDetail staleVersion(StaleVersionException ex) {
        return problem(HttpStatus.PRECONDITION_FAILED, "precondition-failed", "Versión desactualizada",
                ex.getMessage());
    }

    @ExceptionHandler(ConcurrentUpdateException.class)
    ProblemDetail concurrentUpdate(ConcurrentUpdateException ex) {
        return problem(HttpStatus.CONFLICT, "concurrent-modification", "Modificación concurrente", ex.getMessage());
    }

    // ----- 404 / 409 / 422 -----

    @ExceptionHandler(ProductNotFoundException.class)
    ProblemDetail productNotFound(ProductNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "product-not-found", "Producto no encontrado", ex.getMessage());
    }

    @ExceptionHandler(CustomerNotFoundException.class)
    ProblemDetail customerNotFound(CustomerNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "customer-not-found", "Cliente no encontrado", ex.getMessage());
    }

    @ExceptionHandler(DuplicateProductCodeException.class)
    ProblemDetail duplicateProductCode(DuplicateProductCodeException ex) {
        return problem(HttpStatus.CONFLICT, "product-code-conflict", "Código de producto en uso", ex.getMessage());
    }

    @ExceptionHandler(DuplicateCustomerDocumentException.class)
    ProblemDetail duplicateCustomerDocument(DuplicateCustomerDocumentException ex) {
        return problem(HttpStatus.CONFLICT, "customer-document-conflict", "Documento de cliente en uso",
                ex.getMessage());
    }

    /** Cualquier regla de los datos maestros. El mensaje del modelo está escrito para el usuario. */
    @ExceptionHandler(CatalogRuleException.class)
    ProblemDetail businessRule(CatalogRuleException ex) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "business-rule-violation", "Regla de negocio incumplida",
                ex.getMessage());
    }

    // ----- 500 -----

    /** Todo lo no previsto: se registra completo en el log y al cliente no se le muestra nada interno. */
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception ex) {
        LOG.error("Error no controlado", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Error interno",
                "Ocurrió un error inesperado. Si persiste, contacte al soporte.");
    }

    // ----- Internos -----

    private static ProblemDetail problem(HttpStatus status, String slug, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_PREFIX + slug));
        problem.setTitle(title);
        return problem;
    }

    private static Map<String, String> error(String field, String message) {
        return Map.of("field", field == null ? "?" : field, "message", message == null ? "es inválido" : message);
    }

    private static String message(MessageSourceResolvable error) {
        return error.getDefaultMessage();
    }

    /** Ruta del campo al estilo de Bean Validation: {@code taxes[0].rate}. */
    private static String jsonPath(JacksonException exception) {
        StringBuilder path = new StringBuilder();
        for (JacksonException.Reference reference : exception.getPath()) {
            if (reference.getPropertyName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(reference.getPropertyName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.isEmpty() ? null : path.toString();
    }

    private static <T extends Throwable> T findCause(Throwable error, Class<T> type) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return type.cast(cause);
            }
        }
        return null;
    }
}
