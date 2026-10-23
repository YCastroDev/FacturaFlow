package com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus;

import com.facturaflow.invoicing.application.exception.ElectronicInvoiceProviderUnavailableException.Reason;
import java.time.Duration;

/**
 * Falla técnica dentro del adaptador (red, timeout, HTTP inesperado, respuesta ilegible o que no
 * corresponde al documento, circuito abierto, límite de peticiones). No sale del paquete: el
 * adaptador la traduce a {@code ElectronicInvoiceProviderUnavailableException}, con su motivo y su
 * {@code retryAfter} (ADR 0010). El mensaje va a los logs, así que nunca lleva tokens, credenciales
 * ni la URL pública del documento.
 */
final class FactusTechnicalException extends RuntimeException {

    private final Reason reason;
    private final Duration retryAfter;

    FactusTechnicalException(String message) {
        this(message, null);
    }

    FactusTechnicalException(String message, Throwable cause) {
        this(message, Reason.FAILURE, null, cause);
    }

    /** @param retryAfter {@code null} si no se sabe cuándo reintentar */
    FactusTechnicalException(String message, Reason reason, Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.retryAfter = retryAfter;
    }

    Reason reason() {
        return reason;
    }

    /** {@code null} si no se sabe. */
    Duration retryAfter() {
        return retryAfter;
    }
}
