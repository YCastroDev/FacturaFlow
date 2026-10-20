package com.facturaflow.invoicing.domain.exception;

import java.math.BigDecimal;

public final class InvalidQuantityException extends DomainException {

    private InvalidQuantityException(String message) {
        super(message);
    }

    public static InvalidQuantityException missing() {
        return new InvalidQuantityException("La cantidad es obligatoria.");
    }

    public static InvalidQuantityException notPositive(BigDecimal value) {
        return new InvalidQuantityException("La cantidad debe ser mayor que cero: " + value.toPlainString() + ".");
    }

    public static InvalidQuantityException tooManyDecimals(BigDecimal value, int maxDecimals) {
        return new InvalidQuantityException("La cantidad admite máximo " + maxDecimals
                + " decimales: " + value.toPlainString() + ".");
    }
}
