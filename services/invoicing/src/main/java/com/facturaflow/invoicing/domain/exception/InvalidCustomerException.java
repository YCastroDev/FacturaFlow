package com.facturaflow.invoicing.domain.exception;

public final class InvalidCustomerException extends DomainException {

    private InvalidCustomerException(String message) {
        super(message);
    }

    public static InvalidCustomerException missingIdentification() {
        return new InvalidCustomerException("El documento de identificación del cliente es obligatorio.");
    }

    public static InvalidCustomerException missingName() {
        return new InvalidCustomerException(
                "El cliente debe tener razón social (empresa) o nombres (persona natural).");
    }

    public static InvalidCustomerException invalidEmail(String email) {
        return new InvalidCustomerException("El email del cliente no tiene un formato válido: '" + email + "'.");
    }
}
