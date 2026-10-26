package com.facturaflow.sales.domain.exception;

public final class InvalidPaymentTermsException extends DomainException {

    private InvalidPaymentTermsException(String message) {
        super(message);
    }

    public static InvalidPaymentTermsException missingDueDate() {
        return new InvalidPaymentTermsException("Una venta a crédito requiere fecha de vencimiento.");
    }

    public static InvalidPaymentTermsException dueDateNotAfterSaleDate(String dueDate, String saleDate) {
        return new InvalidPaymentTermsException("La fecha de vencimiento (" + dueDate
                + ") debe ser posterior a la fecha de la venta (" + saleDate + ").");
    }
}
