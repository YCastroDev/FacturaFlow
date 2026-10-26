package com.facturaflow.sales.domain.model;

import com.facturaflow.sales.domain.exception.InvalidSaleLineException;

/**
 * Un impuesto aplicado a una línea: qué tributo es y con qué tarifa. COPIA DELIBERADA de invoicing
 * (ADR 0012), con el mismo redondeo.
 */
public record LineTax(TaxType type, TaxRate rate) {

    public LineTax {
        if (type == null) {
            throw InvalidSaleLineException.missing("el tipo de cada impuesto");
        }
        if (rate == null) {
            throw InvalidSaleLineException.missing("la tarifa de cada impuesto");
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
