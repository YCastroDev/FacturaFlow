package com.facturaflow.catalog.model;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Documento de identificación del cliente: su identidad en el catálogo (único por tipo + número,
 * inmutable). {@code checkDigit} es obligatorio para NIT, debe coincidir con el calculado y está
 * prohibido en los demás tipos. El número se guarda sin espacios a los lados y en mayúsculas.
 *
 * <p>COPIA DELIBERADA de {@code IdentificationDocument} de invoicing (ADR 0001, ADR 0011): mismos
 * formatos (numérico de 3 a 15 dígitos; alfanumérico de 3 a 20 para documentos extranjeros).
 */
public record CustomerDocument(DocumentType type, String number, Integer checkDigit) {

    private static final Pattern NUMERIC = Pattern.compile("[0-9]{3,15}");
    private static final Pattern ALPHANUMERIC = Pattern.compile("[A-Z0-9]{3,20}");

    public CustomerDocument {
        if (type == null) {
            throw CatalogRuleException.missing("el tipo de documento");
        }
        number = normalizeNumber(number);
        Pattern format = type.alphanumeric() ? ALPHANUMERIC : NUMERIC;
        if (number == null || !format.matcher(number).matches()) {
            throw new CatalogRuleException("El número de " + type.description() + " no tiene un formato válido"
                    + (type.alphanumeric() ? " (de 3 a 20 letras o dígitos)." : " (de 3 a 15 dígitos)."));
        }
        if (type.requiresCheckDigit()) {
            if (checkDigit == null) {
                throw CatalogRuleException.missing("el dígito de verificación del NIT " + number);
            }
            int expected = NitCheckDigit.calculate(number);
            if (checkDigit != expected) {
                throw new CatalogRuleException("El dígito de verificación del NIT " + number + " es " + expected
                        + ", no " + checkDigit + ".");
            }
        } else if (checkDigit != null) {
            throw new CatalogRuleException("El dígito de verificación solo aplica al NIT, no a "
                    + type.description() + ".");
        }
    }

    /** Normalización del número, la misma para guardar y para buscar. */
    public static String normalizeNumber(String number) {
        return number == null ? null : number.strip().toUpperCase(Locale.ROOT);
    }

    public static CustomerDocument nit(String number, int checkDigit) {
        return new CustomerDocument(DocumentType.NIT, number, checkDigit);
    }

    public static CustomerDocument of(DocumentType type, String number) {
        return new CustomerDocument(type, number, null);
    }
}
