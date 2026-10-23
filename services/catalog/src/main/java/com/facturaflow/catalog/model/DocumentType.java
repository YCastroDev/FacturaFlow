package com.facturaflow.catalog.model;

/** Tipos de documento de identificación (copia de invoicing, con los mismos nombres y reglas). */
public enum DocumentType {
    CITIZENSHIP_CARD("cédula de ciudadanía", false, false),
    NIT("NIT", true, false),
    FOREIGNER_ID_CARD("cédula de extranjería", false, false),
    PASSPORT("pasaporte", false, true),
    IDENTITY_CARD("tarjeta de identidad", false, false),
    CIVIL_REGISTRY("registro civil", false, false),
    FOREIGNER_CARD("tarjeta de extranjería", false, false),
    FOREIGN_ID_DOCUMENT("documento de identificación extranjero", false, true),
    SPECIAL_STAY_PERMIT("permiso especial de permanencia (PEP)", false, true),
    TEMPORARY_PROTECTION_PERMIT("permiso por protección temporal (PPT)", false, true),
    FOREIGN_NIT("NIT de otro país", false, true);

    private final String description;
    private final boolean requiresCheckDigit;
    private final boolean alphanumeric;

    DocumentType(String description, boolean requiresCheckDigit, boolean alphanumeric) {
        this.description = description;
        this.requiresCheckDigit = requiresCheckDigit;
        this.alphanumeric = alphanumeric;
    }

    /** Nombre legible en español, para mensajes. */
    public String description() {
        return description;
    }

    public boolean requiresCheckDigit() {
        return requiresCheckDigit;
    }

    /** Si el número admite letras (documentos extranjeros) o solo dígitos. */
    public boolean alphanumeric() {
        return alphanumeric;
    }
}
