package com.facturaflow.invoicing.domain.model;

import com.facturaflow.invoicing.domain.exception.InvalidReferenceCodeException;
import java.util.regex.Pattern;

/**
 * Identificador único de la venta que origina la factura. Se usa como clave de
 * idempotencia: la misma venta nunca debe facturarse dos veces.
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
