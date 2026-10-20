package com.facturaflow.invoicing.domain.model;

import com.facturaflow.invoicing.domain.exception.InvalidQuantityException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Cantidad vendida: mayor que cero y con máximo {@link #MAX_DECIMALS} decimales. */
public record Quantity(BigDecimal value) {

    /** Único lugar donde se define la precisión de las cantidades. */
    public static final int MAX_DECIMALS = 2;

    public Quantity {
        if (value == null) {
            throw InvalidQuantityException.missing();
        }
        if (value.signum() <= 0) {
            throw InvalidQuantityException.notPositive(value);
        }
        if (value.stripTrailingZeros().scale() > MAX_DECIMALS) {
            throw InvalidQuantityException.tooManyDecimals(value, MAX_DECIMALS);
        }
        value = value.setScale(MAX_DECIMALS, RoundingMode.UNNECESSARY);
    }

    public static Quantity of(String value) {
        return new Quantity(new BigDecimal(value));
    }
}
