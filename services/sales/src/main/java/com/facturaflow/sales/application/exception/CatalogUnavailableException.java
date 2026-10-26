package com.facturaflow.sales.application.exception;

import java.time.Duration;
import java.util.Objects;

/**
 * 503: catalog no respondió (timeout, red, 5xx) o su circuito está abierto. La venta NO se crea; el
 * cliente puede reintentar la misma petición después de {@link #retryAfter()} (ADR 0012).
 */
public class CatalogUnavailableException extends RuntimeException {

    /** Por qué no se pudo consultar catalog. */
    public enum Reason {
        /** La llamada se hizo y falló. */
        FAILURE,
        /** No se llamó: el circuito está abierto tras fallas repetidas. */
        CIRCUIT_OPEN
    }

    private final Reason reason;
    private final Duration retryAfter;

    public CatalogUnavailableException(String message, Reason reason, Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
    }

    public Reason reason() {
        return reason;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
