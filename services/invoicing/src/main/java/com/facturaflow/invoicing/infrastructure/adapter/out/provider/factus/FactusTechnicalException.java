package com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus;

/**
 * Falla técnica dentro del adaptador (red, timeout, HTTP inesperado, respuesta ilegible o que no
 * corresponde a la factura). No sale del paquete: el adaptador la traduce a
 * {@code ElectronicInvoiceProviderUnavailableException}. El mensaje va a los logs, así que nunca
 * lleva tokens, credenciales ni la URL pública del documento.
 */
final class FactusTechnicalException extends RuntimeException {

    FactusTechnicalException(String message) {
        super(message);
    }

    FactusTechnicalException(String message, Throwable cause) {
        super(message, cause);
    }
}
