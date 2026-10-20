package com.facturaflow.invoicing.domain.exception;

public final class InvalidInvoiceException extends DomainException {

    private InvalidInvoiceException(String message) {
        super(message);
    }

    public static InvalidInvoiceException missing(String field) {
        return new InvalidInvoiceException("La factura requiere " + field + ".");
    }

    public static InvalidInvoiceException noLines() {
        return new InvalidInvoiceException("La factura debe tener al menos una línea.");
    }

    public static InvalidInvoiceException observationTooLong(int length, int maxLength) {
        return new InvalidInvoiceException("La observación admite máximo " + maxLength + " caracteres (tiene "
                + length + ").");
    }

    public static InvalidInvoiceException missingRejectionReason() {
        return new InvalidInvoiceException("El rechazo de una factura requiere un motivo.");
    }
}
