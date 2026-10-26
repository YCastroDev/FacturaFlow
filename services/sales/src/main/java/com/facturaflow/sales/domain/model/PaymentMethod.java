package com.facturaflow.sales.domain.model;

/** Medio de pago de la venta (los mismos valores que acepta invoicing). */
public enum PaymentMethod {
    CASH,
    BANK_TRANSFER,
    BANK_DEPOSIT,
    DEBIT_CARD,
    CREDIT_CARD,
    CHECK
}
