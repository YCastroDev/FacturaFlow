package com.facturaflow.sales.application.port.in;

import com.facturaflow.sales.domain.model.DocumentType;
import java.time.Instant;
import java.util.UUID;

/**
 * Un resultado de facturación que informó invoicing (ADR 0015), con tipos simples. La venta se encuentra por
 * {@code referenceCode} (el de la factura es el de la venta); los demás datos sirven para comprobar que el resultado
 * es de ESA venta y para guardarlo.
 */
public sealed interface RecordInvoiceResultCommand {

    String referenceCode();

    /** Cuándo ocurrió el hecho en invoicing. */
    Instant occurredAt();

    /** La factura se emitió. El documento del cliente es el de la factura. */
    record InvoiceIssued(String referenceCode, DocumentType documentType, String documentNumber, UUID invoiceId,
                         String number, String cufe, Instant occurredAt) implements RecordInvoiceResultCommand {
    }

    /** La DIAN rechazó la factura. */
    record InvoiceRejected(String referenceCode, DocumentType documentType, String documentNumber, UUID invoiceId,
                           String reason, Instant occurredAt) implements RecordInvoiceResultCommand {
    }

    /**
     * Invoicing rechazó la petición: no hay factura.
     *
     * @param saleId el de la petición, si invoicing lo pudo leer ({@code null} si no)
     */
    record InvoiceRequestRejected(String referenceCode, UUID saleId, String reason, Instant occurredAt)
            implements RecordInvoiceResultCommand {
    }
}
