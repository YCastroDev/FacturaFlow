package com.facturaflow.invoicing.application.port.in;

import java.util.UUID;

/**
 * Una petición de factura que no se pudo procesar con esos datos (ADR 0015).
 *
 * @param sourceId      identificador de la petición en su origen (p. ej. la venta), o {@code null}
 * @param sourceEventId evento que pidió la factura, o {@code null}
 * @param reason        motivo, para quien pidió la factura (sin datos sensibles)
 */
public record RejectInvoiceRequestCommand(String referenceCode, UUID sourceId, UUID sourceEventId, String reason) {
}
