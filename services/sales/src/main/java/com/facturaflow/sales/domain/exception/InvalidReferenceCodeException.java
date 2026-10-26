package com.facturaflow.sales.domain.exception;

public final class InvalidReferenceCodeException extends DomainException {

    public InvalidReferenceCodeException(String value, int maxLength) {
        super("El código de referencia es obligatorio, de máximo " + maxLength
                + " caracteres y solo puede contener letras, números, '-' y '_': '" + value + "'.");
    }
}
