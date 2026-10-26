package com.facturaflow.sales.infrastructure.adapter.in.rest.dto;

/**
 * Estado de la venta (ADR 0015): CONFIRMED (pendiente de facturar), INVOICED (factura emitida) o INVOICE_FAILED (no
 * se pudo facturar; ver {@code invoiceFailure}).
 */
public enum ApiSaleStatus {
    CONFIRMED,
    INVOICED,
    INVOICE_FAILED
}
