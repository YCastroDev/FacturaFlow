package com.facturaflow.sales.application.exception;

import java.util.UUID;

/** La venta cambió (o desapareció) desde que se leyó: hay que volver a leerla y reintentar (ADR 0015). */
public final class SaleConcurrentModificationException extends RuntimeException {

    private final UUID saleId;

    public SaleConcurrentModificationException(UUID saleId, Throwable cause) {
        super("La venta " + saleId + " fue modificada por otro proceso desde que se leyó.", cause);
        this.saleId = saleId;
    }

    public UUID saleId() {
        return saleId;
    }
}
