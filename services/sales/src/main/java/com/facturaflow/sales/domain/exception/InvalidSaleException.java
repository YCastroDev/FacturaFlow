package com.facturaflow.sales.domain.exception;

public final class InvalidSaleException extends DomainException {

    private InvalidSaleException(String message) {
        super(message);
    }

    public static InvalidSaleException missing(String field) {
        return new InvalidSaleException("La venta requiere " + field + ".");
    }

    public static InvalidSaleException lineCount(int count, int max) {
        return new InvalidSaleException("La venta debe tener entre 1 y " + max + " líneas (recibidas: " + count + ").");
    }

    public static InvalidSaleException observationTooLong(int length, int max) {
        return new InvalidSaleException("La observación admite máximo " + max + " caracteres (recibidos: " + length
                + ").");
    }

    public static InvalidSaleException invalidCustomerDocument(String number) {
        return new InvalidSaleException("El número de documento del cliente debe tener de 3 a 20 letras o dígitos: '"
                + number + "'.");
    }
}
