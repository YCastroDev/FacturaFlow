package com.facturaflow.sales.domain.model;

/**
 * Responsabilidad tributaria del cliente: el tributo del que es responsable ante la DIAN
 * (ADR 0007). Solo los valores que el proveedor acepta hoy; el adaptador los traduce a los
 * códigos de la DIAN.
 */
public enum TaxScheme {
    IVA("responsable de IVA"),
    NOT_APPLICABLE("no aplica");

    private final String description;

    TaxScheme(String description) {
        this.description = description;
    }

    /** Nombre legible en español, para mensajes. */
    public String description() {
        return description;
    }
}
