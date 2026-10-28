package com.facturaflow.gateway.web;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Los errores PROPIOS del gateway como Problem Details (RFC 9457), con el mismo formato que los servicios: {@code type}
 * {@code urn:facturaflow:problem:<slug>}, sin trazas, mensajes de Spring ni direcciones internas. Las respuestas de los
 * servicios (también sus errores) pasan intactas; esto solo cubre lo que el gateway responde por sí mismo. Tabla en el
 * ADR 0017.
 */
@RestControllerAdvice
class ProblemDetailsHandler extends ResponseEntityExceptionHandler {

    static final String TYPE_PREFIX = "urn:facturaflow:problem:";

    private static final Logger LOG = LoggerFactory.getLogger(ProblemDetailsHandler.class);

    /** Ninguna ruta coincide: la petición no llega a ningún servicio. */
    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException ex, HttpHeaders headers,
                                                                    HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "not-found", "Recurso no encontrado",
                "No existe ningún recurso en esta ruta. Las rutas de la API empiezan por /api/v1/.");
        return handleExceptionInternal(ex, problem, headers, HttpStatus.NOT_FOUND, request);
    }

    /**
     * El servicio no respondió: sin conexión (caído, reiniciándose) → 502; conectó pero no respondió a tiempo → 504.
     * Se registra solo la causa (clase), nunca la URL interna ni cabeceras (en la Fase 2 llevan el token).
     */
    @ExceptionHandler(ResourceAccessException.class)
    ProblemDetail upstreamFailure(ResourceAccessException ex) {
        if (isTimeout(ex)) {
            LOG.warn("Un servicio no respondió a tiempo ({})", rootCause(ex).getClass().getSimpleName());
            return problem(HttpStatus.GATEWAY_TIMEOUT, "upstream-timeout", "El servicio no respondió a tiempo",
                    "El servicio que atiende esta ruta no respondió a tiempo. La operación pudo haberse completado: "
                            + "consulte su estado antes de repetirla.");
        }
        LOG.warn("No se pudo contactar al servicio ({})", rootCause(ex).getClass().getSimpleName());
        return problem(HttpStatus.BAD_GATEWAY, "upstream-unavailable", "Servicio no disponible",
                "El servicio que atiende esta ruta no está disponible en este momento. Intente de nuevo más tarde.");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception ex) {
        LOG.error("Error no controlado", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Error interno",
                "Ocurrió un error inesperado. Si persiste, contacte al soporte.");
    }

    // ----- Internos -----

    private static boolean isTimeout(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static Throwable rootCause(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static ProblemDetail problem(HttpStatus status, String slug, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_PREFIX + slug));
        problem.setTitle(title);
        return problem;
    }
}
