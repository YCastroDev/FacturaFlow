package com.facturaflow.invoicing.infrastructure.adapter.in.messaging;

/**
 * El mensaje no cumple el contrato de {@code SaleConfirmed} v1 en lo que invoicing necesita: otro tipo o versión
 * de evento, un campo obligatorio ausente o un valor de enum desconocido. Es un mensaje venenoso: va directo a la
 * DLQ, porque reintentarlo daría siempre el mismo resultado (ADR 0014).
 */
final class InvalidSaleConfirmedMessageException extends RuntimeException {

    InvalidSaleConfirmedMessageException(String message) {
        super(message);
    }
}
