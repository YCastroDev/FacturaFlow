package com.facturaflow.sales.domain.model;

import java.math.BigDecimal;

/** Tarifa de impuesto en porcentaje (19.00 = 19%).
 *
 * <p>COPIA DELIBERADA de invoicing (ADR 0012): mismo cálculo y mismos casos de prueba. Si cambia allí,
 * cambia aquí.
 */
public record TaxRate(BigDecimal percent) {

    public static final TaxRate ZERO = new TaxRate(BigDecimal.ZERO);

    public TaxRate {
        percent = Percentages.validate("impuesto", percent);
    }

    public static TaxRate of(String percent) {
        return new TaxRate(new BigDecimal(percent));
    }

    public boolean isZero() {
        return percent.signum() == 0;
    }
}
