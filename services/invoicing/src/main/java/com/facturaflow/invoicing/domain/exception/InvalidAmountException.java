package com.facturaflow.invoicing.domain.exception;

import java.math.BigDecimal;

public final class InvalidAmountException extends DomainException {

    private InvalidAmountException(String message) {
        super(message);
    }

    public static InvalidAmountException missing() {
        return new InvalidAmountException("El valor monetario es obligatorio.");
    }

    public static InvalidAmountException negative(BigDecimal value) {
        return new InvalidAmountException("El valor monetario no puede ser negativo: " + value.toPlainString() + ".");
    }

    public static InvalidAmountException tooManyDecimals(BigDecimal value, int maxDecimals) {
        return new InvalidAmountException("El valor monetario admite máximo " + maxDecimals
                + " decimales: " + value.toPlainString() + ".");
    }
}
