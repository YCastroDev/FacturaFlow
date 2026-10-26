package com.facturaflow.sales.domain.model;

/**
 * Estado de la venta (ADR 0012 y 0015). Nace {@link #CONFIRMED} (pendiente de facturar) y lo cambian los eventos de
 * invoicing:
 * <ul>
 *   <li>CONFIRMED → {@link #INVOICED} (factura emitida) o {@link #INVOICE_FAILED} (rechazada o petición rechazada);</li>
 *   <li>INVOICE_FAILED → INVOICED: "el éxito gana". Una factura emitida es un hecho legal y llega a ocurrir (p. ej.
 *       tras reprocesar), aunque antes llegara un fallo;</li>
 *   <li>INVOICED es final: un fallo tardío no deshace una factura emitida.</li>
 * </ul>
 */
public enum SaleStatus {
    CONFIRMED,
    INVOICED,
    INVOICE_FAILED;

    public boolean canTransitionTo(SaleStatus target) {
        return switch (this) {
            case CONFIRMED -> target == INVOICED || target == INVOICE_FAILED;
            case INVOICE_FAILED -> target == INVOICED;
            case INVOICED -> false;
        };
    }
}
