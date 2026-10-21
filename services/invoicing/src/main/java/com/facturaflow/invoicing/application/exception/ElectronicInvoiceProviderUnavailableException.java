package com.facturaflow.invoicing.application.exception;

import java.util.UUID;

/**
 * Falla TÉCNICA del proveedor de facturación electrónica (caído, timeout, respuesta
 * ilegible). No dice nada sobre la factura: sigue PENDING y la emisión se puede reintentar.
 * El mensaje es para los logs; nunca se expone al cliente.
 */
public final class ElectronicInvoiceProviderUnavailableException extends RuntimeException {

    private final UUID invoiceId;

    public ElectronicInvoiceProviderUnavailableException(UUID invoiceId, String detail, Throwable cause) {
        super("El proveedor de facturación electrónica no está disponible para emitir la factura " + invoiceId
                + ": " + detail, cause);
        this.invoiceId = invoiceId;
    }

    public UUID invoiceId() {
        return invoiceId;
    }
}
