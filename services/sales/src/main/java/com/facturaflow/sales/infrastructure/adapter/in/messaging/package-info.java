/**
 * Adaptador de entrada de los resultados de facturación (ADR 0015): consume {@code InvoiceIssued},
 * {@code InvoiceRejected} e {@code InvoiceRequestRejected} de invoicing desde RabbitMQ y los aplica a la venta con
 * {@code RecordInvoiceResultUseCase}. Mismo mecanismo que el consumidor de invoicing (ADR 0014: ack manual, esperas
 * con TTL, DLQ), copiado a propósito (sin código compartido, ADR 0001). Anti-Corruption Layer, todo package-private:
 * los DTOs de los eventos y la topología no salen de este paquete (regla 12 de ArchUnit).
 */
package com.facturaflow.sales.infrastructure.adapter.in.messaging;
