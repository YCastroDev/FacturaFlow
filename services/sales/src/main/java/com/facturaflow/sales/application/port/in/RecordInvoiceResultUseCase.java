package com.facturaflow.sales.application.port.in;

/**
 * Aplica a la venta el resultado de su facturación (ADR 0015): INVOICED o INVOICE_FAILED. Idempotente: un resultado
 * repetido no cambia nada ni falla; con eventos desordenados, "el éxito gana y el fallo no deshace".
 */
public interface RecordInvoiceResultUseCase {

    /**
     * @throws com.facturaflow.sales.application.exception.SaleConcurrentModificationException si la venta cambió
     *         mientras se aplicaba (se puede reintentar)
     * @throws com.facturaflow.sales.domain.exception.DomainException si el resultado viene incompleto
     */
    RecordInvoiceResultOutcome record(RecordInvoiceResultCommand command);
}
