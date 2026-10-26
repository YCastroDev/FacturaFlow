package com.facturaflow.sales.domain.model;

/**
 * Estado de la venta. Hoy una venta nace y queda {@link #CONFIRMED}: no hay carrito ni borrador.
 *
 * <p>Preparado para la tarea de eventos (ADR 0012): cuando invoicing informe el resultado llegarán
 * {@code INVOICED} (factura emitida) e {@code INVOICE_FAILED} (rechazada), ambos desde CONFIRMED. Para
 * agregarlos: el valor aquí, su caso en {@link #canTransitionTo}, la migración (CHECK) y el método de
 * {@link Sale} que haga la transición.
 */
public enum SaleStatus {
    CONFIRMED;

    /** Transiciones permitidas. Hoy ninguna: CONFIRMED es el único estado. */
    public boolean canTransitionTo(SaleStatus target) {
        return switch (this) {
            case CONFIRMED -> false;
        };
    }
}
