package com.facturaflow.sales.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Identidad de la venta. */
public record SaleId(UUID value) {

    public SaleId {
        Objects.requireNonNull(value, "value");
    }

    public static SaleId generate() {
        return new SaleId(UUID.randomUUID());
    }
}
