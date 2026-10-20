package com.facturaflow.invoicing.domain.model;

import com.facturaflow.invoicing.domain.exception.InvalidInvoiceLineException;

/** Un impuesto aplicado a una línea: qué tributo es y con qué tarifa. */
public record LineTax(TaxType type, TaxRate rate) {

    public LineTax {
        if (type == null) {
            throw InvalidInvoiceLineException.missing("el tipo de cada impuesto");
        }
        if (rate == null) {
            throw InvalidInvoiceLineException.missing("la tarifa de cada impuesto");
        }
    }

    public static LineTax of(TaxType type, String percent) {
        return new LineTax(type, TaxRate.of(percent));
    }

    /** Valor de este impuesto sobre una base gravable, redondeado HALF_UP por separado. */
    Money amountOn(Money taxableAmount) {
        return taxableAmount.percentage(rate.percent());
    }
}
