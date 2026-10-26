package com.facturaflow.invoicing.application.port.in;

/**
 * Registra que una petición de factura no se convirtió en factura (ADR 0015): publica
 * {@code InvoiceRequestRejected} para que quien la pidió lo sepa (sales marca la venta INVOICE_FAILED). Solo para
 * resultados DEFINITIVOS (los datos no sirven); una falla técnica no se registra, porque se puede reintentar.
 */
public interface RejectInvoiceRequestUseCase {

    /** @throws com.facturaflow.invoicing.domain.exception.DomainException si falta el código o el motivo */
    void reject(RejectInvoiceRequestCommand command);
}
