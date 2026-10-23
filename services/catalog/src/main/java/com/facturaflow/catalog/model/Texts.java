package com.facturaflow.catalog.model;

/** Normalización común de textos: sin espacios a los lados; vacío = ausente. */
final class Texts {

    private Texts() {
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    static String required(String value, String missingDescription, String field, int maxLength) {
        String stripped = blankToNull(value);
        if (stripped == null) {
            throw CatalogRuleException.missing(missingDescription);
        }
        return maxLength(stripped, field, maxLength);
    }

    static String maxLength(String value, String field, int maxLength) {
        if (value != null && value.length() > maxLength) {
            throw CatalogRuleException.tooLong(field, maxLength);
        }
        return value;
    }
}
