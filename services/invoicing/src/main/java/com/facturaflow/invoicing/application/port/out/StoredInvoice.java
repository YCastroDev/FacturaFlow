package com.facturaflow.invoicing.application.port.out;

import com.facturaflow.invoicing.domain.model.Invoice;
import java.util.Objects;

/** Una factura tal como está guardada, junto con la huella de la petición que la creó. */
public record StoredInvoice(Invoice invoice, RequestHash requestHash) {

    public StoredInvoice {
        Objects.requireNonNull(invoice, "invoice");
        Objects.requireNonNull(requestHash, "requestHash");
    }
}
