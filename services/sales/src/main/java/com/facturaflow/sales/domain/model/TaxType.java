package com.facturaflow.sales.domain.model;

/** Tipos de impuesto de una línea (los mismos que en catalog e invoicing). */
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
