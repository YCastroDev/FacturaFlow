package com.facturaflow.invoicing.application.port.out;

import com.facturaflow.invoicing.domain.model.InvoiceEvent;

/**
 * Puerto de salida: guarda en el outbox un evento que NO viene de un cambio de estado de la factura (ADR 0015),
 * en su propia transacción. Hoy solo {@link InvoiceEvent.InvoiceRequestRejected}: no hay factura que guardar. Los
 * eventos de la factura entran con {@link InvoiceRepository#save}, junto con su cambio de estado.
 */
public interface InvoiceEventStore {

    void append(InvoiceEvent.InvoiceRequestRejected event);
}
