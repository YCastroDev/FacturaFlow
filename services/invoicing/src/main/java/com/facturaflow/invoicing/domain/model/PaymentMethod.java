package com.facturaflow.invoicing.domain.model;

/** Medio de pago propio del dominio; el adaptador lo traduce al código de Factus/DIAN. */
public enum PaymentMethod {
    CASH,
    BANK_TRANSFER,
    BANK_DEPOSIT,
    DEBIT_CARD,
    CREDIT_CARD,
    CHECK
}
