package com.facturaflow.sales.domain.model;

import com.facturaflow.sales.domain.exception.InvalidReferenceCodeException;
import java.util.regex.Pattern;

/**
 * Código único de la venta: clave de idempotencia de su creación y, después, el código de referencia
 * de su factura. Mismo formato que en invoicing (ADR 0012).
 */
public record ReferenceCode(String value) {

    public static final int MAX_LENGTH = 50;
    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{1," + MAX_LENGTH + "}");

    public ReferenceCode {
        if (value == null || !FORMAT.matcher(value.strip()).matches()) {
            throw new InvalidReferenceCodeException(value, MAX_LENGTH);
        }
        value = value.strip();
    }
}
