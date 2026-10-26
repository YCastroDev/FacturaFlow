package com.facturaflow.sales.application.port.in;

import com.facturaflow.sales.domain.model.SaleStatus;

/** Qué hizo la venta con un resultado de facturación (ADR 0015, D3). Ninguno es un error: todos se confirman (ack). */
public sealed interface RecordInvoiceResultOutcome {

    /** La venta cambió de estado. */
    record Applied(SaleStatus status) implements RecordInvoiceResultOutcome {
    }

    /** La venta ya tenía ese resultado (evento repetido): no cambia nada. */
    record Duplicate(SaleStatus status) implements RecordInvoiceResultOutcome {
    }

    /**
     * El resultado no aplica a ninguna venta (factura de la API REST, de otra venta o de otro cliente) o llega tarde
     * (un fallo después de una factura emitida). No cambia nada.
     */
    record Ignored(String reason) implements RecordInvoiceResultOutcome {
    }
}
