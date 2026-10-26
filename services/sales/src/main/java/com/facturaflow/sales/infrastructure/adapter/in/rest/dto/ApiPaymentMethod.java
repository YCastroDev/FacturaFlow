package com.facturaflow.sales.infrastructure.adapter.in.rest.dto;

/** Medio de pago en la API (mismos nombres que invoicing). */
public enum ApiPaymentMethod {
    CASH,
    BANK_TRANSFER,
    BANK_DEPOSIT,
    DEBIT_CARD,
    CREDIT_CARD,
    CHECK
}
