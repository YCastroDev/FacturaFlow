package com.facturaflow.sales.domain.exception;

import java.util.UUID;

/** Una transición de estado que la venta no permite (ADR 0015): p. ej. pasar de INVOICED a INVOICE_FAILED. */
public final class InvalidSaleStateTransitionException extends DomainException {

    public InvalidSaleStateTransitionException(UUID saleId, String from, String to) {
        super("La venta " + saleId + " no puede pasar de " + from + " a " + to + ".");
    }
}
