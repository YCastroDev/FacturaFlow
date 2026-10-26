package com.facturaflow.sales.domain.exception;

import java.math.BigDecimal;

public final class InvalidPercentageException extends DomainException {

    private InvalidPercentageException(String message) {
        super(message);
    }

    public static InvalidPercentageException missing(String field) {
        return new InvalidPercentageException("El porcentaje de " + field + " es obligatorio.");
    }

    public static InvalidPercentageException outOfRange(String field, BigDecimal value) {
        return new InvalidPercentageException("El porcentaje de " + field + " debe estar entre 0 y 100: "
                + value.toPlainString() + ".");
    }

    public static InvalidPercentageException tooManyDecimals(String field, BigDecimal value, int maxDecimals) {
        return new InvalidPercentageException("El porcentaje de " + field + " admite máximo " + maxDecimals
                + " decimales: " + value.toPlainString() + ".");
    }
}
