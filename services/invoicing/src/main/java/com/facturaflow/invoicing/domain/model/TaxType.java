package com.facturaflow.invoicing.domain.model;

/** Tipos de impuesto propios del dominio; el adaptador los traduce a los tributos de Factus (ADR 0004). */
public enum TaxType {
    IVA("impuesto al valor agregado"),
    INC("impuesto nacional al consumo");

    private final String description;

    TaxType(String description) {
        this.description = description;
    }

    /** Nombre legible en español, para mensajes. */
    public String description() {
        return description;
    }
}
