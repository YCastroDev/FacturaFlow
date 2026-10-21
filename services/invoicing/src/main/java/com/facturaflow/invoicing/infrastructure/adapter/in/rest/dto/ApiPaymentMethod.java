package com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto;

import com.facturaflow.invoicing.domain.model.PaymentMethod;

/** Medio de pago en el contrato de la API (ver {@link ApiDocumentType} sobre por qué es un enum propio). */
public enum ApiPaymentMethod {
    CASH,
    BANK_TRANSFER,
    BANK_DEPOSIT,
    DEBIT_CARD,
    CREDIT_CARD,
    CHECK;

    public PaymentMethod toDomain() {
        return switch (this) {
            case CASH -> PaymentMethod.CASH;
            case BANK_TRANSFER -> PaymentMethod.BANK_TRANSFER;
            case BANK_DEPOSIT -> PaymentMethod.BANK_DEPOSIT;
            case DEBIT_CARD -> PaymentMethod.DEBIT_CARD;
            case CREDIT_CARD -> PaymentMethod.CREDIT_CARD;
            case CHECK -> PaymentMethod.CHECK;
        };
    }

    public static ApiPaymentMethod from(PaymentMethod method) {
        return switch (method) {
            case CASH -> CASH;
            case BANK_TRANSFER -> BANK_TRANSFER;
            case BANK_DEPOSIT -> BANK_DEPOSIT;
            case DEBIT_CARD -> DEBIT_CARD;
            case CREDIT_CARD -> CREDIT_CARD;
            case CHECK -> CHECK;
        };
    }
}
