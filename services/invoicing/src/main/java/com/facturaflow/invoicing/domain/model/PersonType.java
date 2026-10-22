package com.facturaflow.invoicing.domain.model;

/**
 * Tipo de persona del cliente (ADR 0007). Determina cómo se identifica por nombre: una persona
 * jurídica por su razón social y una natural por sus nombres. No se deduce del tipo de
 * documento: una persona natural comerciante también puede tener NIT.
 */
public enum PersonType {
    NATURAL_PERSON("persona natural"),
    LEGAL_ENTITY("persona jurídica");

    private final String description;

    PersonType(String description) {
        this.description = description;
    }

    /** Nombre legible en español, para mensajes. */
    public String description() {
        return description;
    }
}
