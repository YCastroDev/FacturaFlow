package com.facturaflow.invoicing.domain.model;

import java.math.BigDecimal;

/** Descuento en porcentaje sobre el valor bruto de una línea (10.00 = 10%). */
public record DiscountRate(BigDecimal percent) {

    public static final DiscountRate NONE = new DiscountRate(BigDecimal.ZERO);

    public DiscountRate {
        percent = Percentages.validate("descuento", percent);
    }

    public static DiscountRate of(String percent) {
        return new DiscountRate(new BigDecimal(percent));
    }
}
