package com.facturaflow.sales.infrastructure.adapter.in.messaging;

/** El mensaje no cumple el contrato de un resultado de facturación en lo que sales necesita: va directo a la DLQ. */
final class InvalidInvoiceResultMessageException extends RuntimeException {

    InvalidInvoiceResultMessageException(String message) {
        super(message);
    }
}
