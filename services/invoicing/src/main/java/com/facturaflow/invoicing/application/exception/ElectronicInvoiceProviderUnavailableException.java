package com.facturaflow.invoicing.application.exception;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Falla TÉCNICA del proveedor de facturación electrónica (caído, timeout, respuesta
 * ilegible, límite de peticiones). No dice nada sobre el documento (factura o nota crédito):
 * sigue en su estado y la operación (emitir, descargar el PDF) se puede reintentar. El mensaje
 * es para los logs; nunca se expone al cliente.
 *
 * <p>{@link #reason()} y {@link #retryAfter()} le dicen al cliente por qué y cuándo reintentar
 * (ADR 0010): p. ej. con el circuito abierto, el tiempo que falta para volver a probar.
 */
public final class ElectronicInvoiceProviderUnavailableException extends RuntimeException {

    /** Tipo de documento afectado; da el texto del mensaje al cliente. */
    public enum Document {
        INVOICE("factura", "La factura"),
        CREDIT_NOTE("nota crédito", "La nota crédito");

        private final String name;
        private final String subject;

        Document(String name, String subject) {
            this.name = name;
            this.subject = subject;
        }

        /** Sujeto de una frase: "La factura", "La nota crédito". */
        public String subject() {
            return subject;
        }
    }

    /** Por qué no se pudo usar el proveedor (ADR 0010). */
    public enum Reason {
        /** El proveedor falló o no respondió (tras los reintentos). */
        FAILURE,
        /** Fallas repetidas: se dejó de llamar al proveedor por un tiempo (circuit breaker abierto). */
        CIRCUIT_OPEN,
        /** Se agotó el límite de peticiones hacia el proveedor (propio o del proveedor). */
        RATE_LIMITED
    }

    private final Document document;
    private final UUID documentId;
    private final Reason reason;
    private final Duration retryAfter;

    /** Falla al operar sobre una factura. */
    public ElectronicInvoiceProviderUnavailableException(UUID invoiceId, String detail, Throwable cause) {
        this(Document.INVOICE, invoiceId, detail, cause, Reason.FAILURE, null);
    }

    private ElectronicInvoiceProviderUnavailableException(Document document, UUID documentId, String detail,
                                                          Throwable cause, Reason reason, Duration retryAfter) {
        super("El proveedor de facturación electrónica no está disponible (" + document.name + " " + documentId
                + "): " + detail, cause);
        this.document = document;
        this.documentId = documentId;
        this.reason = reason;
        this.retryAfter = retryAfter;
    }

    /** Falla al operar sobre una nota crédito (ADR 0009). */
    public static ElectronicInvoiceProviderUnavailableException forCreditNote(UUID creditNoteId, String detail,
                                                                             Throwable cause) {
        return new ElectronicInvoiceProviderUnavailableException(Document.CREDIT_NOTE, creditNoteId, detail, cause,
                Reason.FAILURE, null);
    }

    /**
     * Caso general, con el motivo y, si se conoce, cuándo reintentar.
     *
     * @param retryAfter {@code null} si el proveedor no lo indica
     */
    public static ElectronicInvoiceProviderUnavailableException of(Document document, UUID documentId, String detail,
                                                                  Throwable cause, Reason reason,
                                                                  Duration retryAfter) {
        return new ElectronicInvoiceProviderUnavailableException(document, documentId, detail, cause, reason,
                retryAfter);
    }

    public Document document() {
        return document;
    }

    public UUID documentId() {
        return documentId;
    }

    public Reason reason() {
        return reason;
    }

    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
