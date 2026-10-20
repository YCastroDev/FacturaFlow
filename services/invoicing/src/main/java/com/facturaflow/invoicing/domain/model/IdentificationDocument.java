package com.facturaflow.invoicing.domain.model;

import com.facturaflow.invoicing.domain.exception.InvalidIdentificationException;
import java.util.regex.Pattern;

/**
 * Documento de identificación del cliente. {@code checkDigit} solo aplica (y es
 * obligatorio) para NIT; en los demás tipos debe ser {@code null}.
 */
public record IdentificationDocument(DocumentType type, String number, Integer checkDigit) {

    private static final Pattern NUMERIC = Pattern.compile("[0-9]{3,15}");
    private static final Pattern ALPHANUMERIC = Pattern.compile("[A-Za-z0-9]{3,20}");

    public IdentificationDocument {
        if (type == null) {
            throw InvalidIdentificationException.missingType();
        }
        number = number == null ? null : number.strip();
        Pattern format = type.alphanumeric() ? ALPHANUMERIC : NUMERIC;
        if (number == null || !format.matcher(number).matches()) {
            throw InvalidIdentificationException.invalidNumber(type.description(), number);
        }
        if (type.requiresCheckDigit()) {
            if (checkDigit == null) {
                throw InvalidIdentificationException.missingCheckDigit(number);
            }
            int expected = NitCheckDigit.calculate(number);
            if (checkDigit != expected) {
                throw InvalidIdentificationException.wrongCheckDigit(number, checkDigit, expected);
            }
        } else if (checkDigit != null) {
            throw InvalidIdentificationException.unexpectedCheckDigit(type.description());
        }
    }

    public static IdentificationDocument nit(String number, int checkDigit) {
        return new IdentificationDocument(DocumentType.NIT, number, checkDigit);
    }

    public static IdentificationDocument of(DocumentType type, String number) {
        return new IdentificationDocument(type, number, null);
    }
}
