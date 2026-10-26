package com.facturaflow.sales.domain.model;

import java.math.BigDecimal;

/** Descuento en porcentaje sobre el valor bruto de una línea (10.00 = 10%).
 *
 * <p>COPIA DELIBERADA de invoicing (ADR 0012): mismo cálculo y mismos casos de prueba. Si cambia allí,
 * cambia aquí.
 */
public record DiscountRate(BigDecimal percent) {

    public static final DiscountRate NONE = new DiscountRate(BigDecimal.ZERO);

    public DiscountRate {
        percent = Percentages.validate("descuento", percent);
    }

    public static DiscountRate of(String percent) {
        return new DiscountRate(new BigDecimal(percent));
    }
}
