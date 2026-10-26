package com.facturaflow.sales.domain.model;

import com.facturaflow.sales.domain.exception.InvalidPercentageException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Validación común de {@link TaxRate} y {@link DiscountRate}: 0 a 100, máximo 2 decimales.
 *
 * <p>COPIA DELIBERADA de invoicing (ADR 0012): mismo cálculo y mismos casos de prueba. Si cambia allí,
 * cambia aquí.
 */
final class Percentages {

    static final int MAX_DECIMALS = 2;
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private Percentages() {
    }

    static BigDecimal validate(String field, BigDecimal value) {
        if (value == null) {
            throw InvalidPercentageException.missing(field);
        }
        if (value.signum() < 0 || value.compareTo(ONE_HUNDRED) > 0) {
            throw InvalidPercentageException.outOfRange(field, value);
        }
        if (value.stripTrailingZeros().scale() > MAX_DECIMALS) {
            throw InvalidPercentageException.tooManyDecimals(field, value, MAX_DECIMALS);
        }
        return value.setScale(MAX_DECIMALS, RoundingMode.UNNECESSARY);
    }
}
