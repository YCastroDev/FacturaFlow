package com.facturaflow.invoicing.domain.model;

import java.math.BigDecimal;

/** Tarifa de impuesto en porcentaje (19.00 = 19%). */
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
