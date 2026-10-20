package com.facturaflow.invoicing.domain.model;

import com.facturaflow.invoicing.domain.exception.InvalidInvoiceException;
import java.util.UUID;

/** Identidad de la factura. */
public record InvoiceId(UUID value) {

    public InvoiceId {
        if (value == null) {
            throw InvalidInvoiceException.missing("un identificador");
        }
    }

    /** Genera un id nuevo. El dominio lo recibe como parámetro; quien crea la factura decide cuándo generarlo. */
    public static InvoiceId generate() {
        return new InvoiceId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
