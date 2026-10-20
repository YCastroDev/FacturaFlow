package com.facturaflow.invoicing.domain.model;

import com.facturaflow.invoicing.domain.exception.InvalidAmountException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Valor en pesos colombianos (COP), no negativo, siempre con 2 decimales.
 *
 * <p>Política de redondeo: {@link RoundingMode#HALF_UP} a 2 decimales (redondeo
 * comercial, el que usan la DIAN y los proveedores tecnológicos). Solo se aplica a
 * resultados de cálculos ({@link #rounded}); los valores de entrada ({@link #of})
 * deben venir con máximo 2 decimales (ADR 0004).
 */
public record Money(BigDecimal amount) implements Comparable<Money> {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    public static final Money ZERO = new Money(BigDecimal.ZERO);

    public Money {
        if (amount == null) {
            throw InvalidAmountException.missing();
        }
        if (amount.signum() < 0) {
            throw InvalidAmountException.negative(amount);
        }
        if (amount.stripTrailingZeros().scale() > SCALE) {
            throw InvalidAmountException.tooManyDecimals(amount, SCALE);
        }
        amount = amount.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    public static Money of(BigDecimal amount) {
        return new Money(amount);
    }

    public static Money of(String amount) {
        return new Money(new BigDecimal(amount));
    }

    /** Redondea explícitamente el resultado de un cálculo según la política del dominio. */
    static Money rounded(BigDecimal rawAmount) {
        return new Money(rawAmount.setScale(SCALE, ROUNDING));
    }

    public Money plus(Money other) {
        return new Money(amount.add(other.amount));
    }

    public Money minus(Money other) {
        return new Money(amount.subtract(other.amount));
    }

    /** Porcentaje de este valor, redondeado: 1000.00 x 19% = 190.00. */
    Money percentage(BigDecimal percent) {
        return rounded(amount.multiply(percent).movePointLeft(2));
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    @Override
    public int compareTo(Money other) {
        return amount.compareTo(other.amount);
    }

    @Override
    public String toString() {
        return "COP " + amount.toPlainString();
    }
}
