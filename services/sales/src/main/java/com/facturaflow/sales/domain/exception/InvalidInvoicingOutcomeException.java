package com.facturaflow.sales.domain.exception;

/** El resultado de la facturación de una venta está incompleto o es incoherente con su estado (ADR 0015). */
public final class InvalidInvoicingOutcomeException extends DomainException {

    private InvalidInvoicingOutcomeException(String message) {
        super(message);
    }

    public static InvalidInvoicingOutcomeException missing(String field) {
        return new InvalidInvoicingOutcomeException("El resultado de la facturación requiere " + field + ".");
    }

    public static InvalidInvoicingOutcomeException tooLong(String field, int max) {
        return new InvalidInvoicingOutcomeException("El " + field + " admite máximo " + max + " caracteres.");
    }

    public static InvalidInvoicingOutcomeException inconsistent(String status, String detail) {
        return new InvalidInvoicingOutcomeException("Una venta " + status + " " + detail + ".");
    }
}
