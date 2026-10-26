package com.facturaflow.sales.infrastructure.adapter.in.rest;

import com.facturaflow.sales.application.exception.CatalogUnavailableException;
import com.facturaflow.sales.application.exception.DuplicateReferenceCodeException;
import com.facturaflow.sales.application.exception.InvalidCatalogReferencesException;
import com.facturaflow.sales.application.exception.ReferenceCodeConflictException;
import com.facturaflow.sales.application.exception.SaleNotFoundException;
import com.facturaflow.sales.domain.exception.DomainException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
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
 * Traduce excepciones a Problem Details (RFC 9457), con el mismo formato que invoicing y catalog:
 * {@code type} estable {@code urn:facturaflow:problem:<slug>}, sin trazas ni mensajes de Spring, Jackson,
 * Hibernate o de catalog. Solo se devuelven como {@code detail} los mensajes escritos para personas (los
 * del dominio y los de las excepciones de la aplicación). Tabla completa en el ADR 0012.
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

    // ----- 404 / 409 / 422 -----

    @ExceptionHandler(SaleNotFoundException.class)
    ProblemDetail saleNotFound(SaleNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "sale-not-found", "Venta no encontrada", ex.getMessage());
    }

    @ExceptionHandler(ReferenceCodeConflictException.class)
    ProblemDetail referenceCodeConflict(ReferenceCodeConflictException ex) {
        return problem(HttpStatus.CONFLICT, "reference-code-conflict", "Código de referencia en uso", ex.getMessage());
    }

    /** Red de seguridad: el caso de uso ya la resuelve; no debería llegar aquí. */
    @ExceptionHandler(DuplicateReferenceCodeException.class)
    ProblemDetail duplicateReferenceCode(DuplicateReferenceCodeException ex) {
        return problem(HttpStatus.CONFLICT, "reference-code-conflict", "Código de referencia en uso",
                "Ya existe una venta con el código de referencia '" + ex.referenceCode() + "'.");
    }

    /** Cliente o productos inexistentes o inactivos en catalog: todos los problemas juntos, también como datos. */
    @ExceptionHandler(InvalidCatalogReferencesException.class)
    ProblemDetail invalidCatalogReferences(InvalidCatalogReferencesException ex) {
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_CONTENT, "catalog-reference-invalid",
                "Datos de catalog no válidos para la venta", ex.getMessage());
        ex.customerProblem().ifPresent(text -> problem.setProperty("customerProblem", text));
        problem.setProperty("missingProducts", ex.missingProducts());
        problem.setProperty("inactiveProducts", ex.inactiveProducts());
        return problem;
    }

    /** Cualquier otra regla de negocio. El mensaje del dominio está escrito para el usuario. */
    @ExceptionHandler(DomainException.class)
    ProblemDetail businessRule(DomainException ex) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "business-rule-violation", "Regla de negocio incumplida",
                ex.getMessage());
    }

    // ----- 503 -----

    /**
     * Catalog no respondió o su circuito está abierto: la venta NO se creó. El mensaje de la excepción es
     * técnico (para el log); al cliente se le dice por qué y cuándo reintentar.
     */
    @ExceptionHandler(CatalogUnavailableException.class)
    ResponseEntity<ProblemDetail> catalogUnavailable(CatalogUnavailableException ex) {
        if (ex.reason() == CatalogUnavailableException.Reason.FAILURE) {
            LOG.warn("Catalog no disponible: {}", ex.getMessage(), ex.getCause());
        } else {
            LOG.warn("Catalog no disponible: {}", ex.getMessage());   // decisión del circuito, sin traza
        }
        String seconds = Long.toString(Math.max(1, (ex.retryAfter().toMillis() + 999) / 1000));
        String why = switch (ex.reason()) {
            case FAILURE -> "El catálogo de productos y clientes no está disponible.";
            case CIRCUIT_OPEN -> "El catálogo de productos y clientes falló varias veces seguidas y se dejó de "
                    + "consultar por un momento.";
        };
        ProblemDetail problem = problem(HttpStatus.SERVICE_UNAVAILABLE, "catalog-unavailable", "Catálogo no disponible",
                why + " La venta no se creó; reintente la misma petición en " + seconds + " s.");
        problem.setProperty("reason", ex.reason().name().toLowerCase(Locale.ROOT).replace('_', '-'));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, seconds)
                .body(problem);
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

    /** Ruta del campo al estilo de Bean Validation: {@code lines[0].quantity}. */
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
