package com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto;

/** Forma de pago en el contrato de la API: de contado o a crédito (con {@code dueDate}). */
public enum ApiPaymentTermsType {
    CASH,
    CREDIT
}
