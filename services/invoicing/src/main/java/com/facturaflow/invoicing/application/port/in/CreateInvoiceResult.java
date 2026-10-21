package com.facturaflow.invoicing.application.port.in;

import com.facturaflow.invoicing.domain.model.Invoice;
import java.util.Objects;

/**
 * Resultado de crear una factura. {@code created} es {@code false} cuando la petición era
 * un reintento idéntico de una ya existente: se devuelve esa factura y no se crea otra.
 * La factura es de solo lectura para quien la recibe (ADR 0006, D3).
 */
public record CreateInvoiceResult(Invoice invoice, boolean created) {

    public CreateInvoiceResult {
        Objects.requireNonNull(invoice, "invoice");
    }

    public static CreateInvoiceResult created(Invoice invoice) {
        return new CreateInvoiceResult(invoice, true);
    }

    public static CreateInvoiceResult replayed(Invoice invoice) {
        return new CreateInvoiceResult(invoice, false);
    }
}
