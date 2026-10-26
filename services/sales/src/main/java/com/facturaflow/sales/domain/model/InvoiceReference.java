package com.facturaflow.sales.domain.model;

import com.facturaflow.sales.domain.exception.InvalidInvoicingOutcomeException;
import java.util.UUID;

/**
 * La factura electrónica que invoicing emitió para la venta (ADR 0015): su id en invoicing, su número oficial y su
 * CUFE. Sales no los interpreta: los muestra y permite reconocer un evento repetido (misma factura).
 */
public record InvoiceReference(UUID invoiceId, String number, String cufe) {

    public InvoiceReference {
        if (invoiceId == null) {
            throw InvalidInvoicingOutcomeException.missing("el id de la factura");
        }
        number = requireText(number, "el número de la factura");
        cufe = requireText(cufe, "el CUFE de la factura");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw InvalidInvoicingOutcomeException.missing(field);
        }
        return value.strip();
    }
}
