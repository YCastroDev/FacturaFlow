package com.facturaflow.invoicing.domain.model;

import com.facturaflow.invoicing.domain.exception.InvalidCustomerException;
import java.util.regex.Pattern;

/**
 * Teléfono normalizado al estilo E.164: un '+' inicial opcional y de 7 a 15 dígitos. Se
 * aceptan espacios, guiones, puntos y paréntesis como separadores y se eliminan
 * ("(604) 444-5555" → "6044445555").
 */
public record PhoneNumber(String value) {

    public static final int MAX_LENGTH = 16;
    private static final Pattern FORMAT = Pattern.compile("\\+?[0-9]{7,15}");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s().-]");

    public PhoneNumber {
        String normalized = value == null ? null : SEPARATORS.matcher(value).replaceAll("");
        if (normalized == null || !FORMAT.matcher(normalized).matches()) {
            throw InvalidCustomerException.invalidPhone(value);
        }
        value = normalized;
    }

    public static PhoneNumber of(String value) {
        return new PhoneNumber(value);
    }
}
