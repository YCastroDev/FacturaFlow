package com.facturaflow.invoicing.application.service;

import com.facturaflow.invoicing.domain.model.CustomerSnapshot;
import com.facturaflow.invoicing.domain.model.InvoiceLine;
import com.facturaflow.invoicing.domain.model.PaymentMethod;
import com.facturaflow.invoicing.domain.model.PaymentTerms;
import java.util.List;

/**
 * Lo que una petición de creación pide facturar, ya convertido a value objects del dominio
 * (y por lo tanto validado y normalizado). Es todo menos la identidad y la fecha de emisión.
 *
 * @param observation ya normalizada con {@code Invoice.normalizedObservation}
 */
record InvoiceContent(CustomerSnapshot customer, List<InvoiceLine> lines, PaymentMethod paymentMethod,
                      PaymentTerms paymentTerms, String observation) {

    InvoiceContent {
        lines = List.copyOf(lines);
    }
}
