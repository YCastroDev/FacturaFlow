package com.facturaflow.invoicing.infrastructure.adapter.in.rest;

import com.facturaflow.invoicing.application.exception.DuplicateReferenceCodeException;
import com.facturaflow.invoicing.application.exception.ElectronicInvoiceProviderUnavailableException;
import com.facturaflow.invoicing.application.exception.InvoiceAlreadyRejectedException;
import com.facturaflow.invoicing.application.exception.InvoiceConcurrentModificationException;
import com.facturaflow.invoicing.application.exception.InvoiceNotFoundException;
import com.facturaflow.invoicing.application.exception.ReferenceCodeConflictException;
import com.facturaflow.invoicing.domain.exception.DomainException;
import com.facturaflow.invoicing.domain.exception.InvalidInvoiceStateTransitionException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
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
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * Traduce excepciones a Problem Details (RFC 9457) según la tabla del ADR 0006 (D5).
 *
 * <p>Nunca expone trazas ni mensajes internos de Spring, Jackson o Hibernate. Solo se
 * devuelven como {@code detail} los mensajes escritos para personas: los del dominio (reglas
 * de negocio en español) y los de las excepciones propias de la aplicación. Cada problema
 * lleva un {@code type} estable ({@code urn:facturaflow:problem:<slug>}) que los clientes
 * pueden usar para decidir qué hacer sin interpretar textos.
 *
 * <p>Hereda de {@link ResponseEntityExceptionHandler}, que ya convierte en ProblemDetail las
 * excepciones estándar de Spring MVC (404 de ruta, 405, 415...); aquí se sobrescriben las de
 * validación y lectura del cuerpo para dar detalles útiles y seguros.
 */
@RestControllerAdvice
class ProblemDetailsHandler extends ResponseEntityExceptionHandler {

    static final String TYPE_PREFIX = "urn:facturaflow:problem:";
    static final String RETRY_AFTER_SECONDS = "30";

    private static final Logger LOG = LoggerFactory.getLogger(ProblemDetailsHandler.class);

    // ----- 400: forma de la petición -----

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(),
                        "message", error.getDefaultMessage() == null ? "es inválido" : error.getDefaultMessage()))
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "invalid-request", "Petición inválida",
                "La petición tiene campos faltantes o con formato inválido.");
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

    /** Parámetro con tipo incorrecto, p. ej. un id que no es UUID. */
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

    // ----- 404 / 409 / 422: casos de uso y dominio -----

    @ExceptionHandler(InvoiceNotFoundException.class)
    ProblemDetail notFound(InvoiceNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "invoice-not-found", "Factura no encontrada", ex.getMessage());
    }

    @ExceptionHandler(ReferenceCodeConflictException.class)
    ProblemDetail referenceCodeConflict(ReferenceCodeConflictException ex) {
        return problem(HttpStatus.CONFLICT, "reference-code-conflict", "Código de referencia en uso", ex.getMessage());
    }

    @ExceptionHandler(InvoiceAlreadyRejectedException.class)
    ProblemDetail alreadyRejected(InvoiceAlreadyRejectedException ex) {
        return problem(HttpStatus.CONFLICT, "invoice-already-rejected", "Factura rechazada", ex.getMessage());
    }

    @ExceptionHandler(InvoiceConcurrentModificationException.class)
    ProblemDetail concurrentModification(InvoiceConcurrentModificationException ex) {
        return problem(HttpStatus.CONFLICT, "concurrent-modification", "Modificación concurrente",
                "La factura " + ex.invoiceId() + " fue modificada por otra petición al mismo tiempo. "
                        + "Consúltela de nuevo antes de reintentar.");
    }

    /** Red de seguridad: el caso de uso de creación ya la resuelve; no debería llegar aquí. */
    @ExceptionHandler(DuplicateReferenceCodeException.class)
    ProblemDetail duplicateReferenceCode(DuplicateReferenceCodeException ex) {
        return problem(HttpStatus.CONFLICT, "reference-code-conflict", "Código de referencia en uso",
                "Ya existe una factura con el código de referencia '" + ex.referenceCode() + "'.");
    }

    /** Un cambio de estado no permitido es un conflicto con el estado actual, no un dato inválido. */
    @ExceptionHandler(InvalidInvoiceStateTransitionException.class)
    ProblemDetail invalidStateTransition(InvalidInvoiceStateTransitionException ex) {
        return problem(HttpStatus.CONFLICT, "invalid-state-transition", "Transición de estado no permitida",
                ex.getMessage());
    }

    /** Cualquier otra regla de negocio. El mensaje del dominio está escrito para el usuario. */
    @ExceptionHandler(DomainException.class)
    ProblemDetail businessRule(DomainException ex) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "business-rule-violation", "Regla de negocio incumplida",
                ex.getMessage());
    }

    // ----- 503 / 500 -----

    /** El mensaje de la excepción es técnico (para el log); al cliente solo se le dice que reintente. */
    @ExceptionHandler(ElectronicInvoiceProviderUnavailableException.class)
    ResponseEntity<ProblemDetail> providerUnavailable(ElectronicInvoiceProviderUnavailableException ex) {
        LOG.warn("Proveedor de facturación no disponible: {}", ex.getMessage(), ex.getCause());
        ProblemDetail problem = problem(HttpStatus.SERVICE_UNAVAILABLE, "provider-unavailable",
                "Proveedor no disponible",
                "El proveedor de facturación electrónica no está disponible. La factura sigue PENDING; "
                        + "reintente la emisión más tarde.");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(problem);
    }

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
