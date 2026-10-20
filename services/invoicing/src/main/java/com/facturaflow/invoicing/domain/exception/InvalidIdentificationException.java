package com.facturaflow.invoicing.domain.exception;

public final class InvalidIdentificationException extends DomainException {

    private InvalidIdentificationException(String message) {
        super(message);
    }

    public static InvalidIdentificationException missingType() {
        return new InvalidIdentificationException("El tipo de documento de identificación es obligatorio.");
    }

    public static InvalidIdentificationException invalidNumber(String documentType, String number) {
        return new InvalidIdentificationException("El número de " + documentType + " no es válido: '" + number + "'.");
    }

    public static InvalidIdentificationException missingCheckDigit(String number) {
        return new InvalidIdentificationException("El NIT " + number + " requiere dígito de verificación.");
    }

    public static InvalidIdentificationException wrongCheckDigit(String number, int given, int expected) {
        return new InvalidIdentificationException("El dígito de verificación del NIT " + number + " es " + expected
                + ", no " + given + ".");
    }

    public static InvalidIdentificationException unexpectedCheckDigit(String documentType) {
        return new InvalidIdentificationException("Solo el NIT lleva dígito de verificación, no la " + documentType + ".");
    }
}
