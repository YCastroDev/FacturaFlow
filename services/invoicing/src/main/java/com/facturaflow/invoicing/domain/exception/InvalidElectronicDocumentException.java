package com.facturaflow.invoicing.domain.exception;

public final class InvalidElectronicDocumentException extends DomainException {

    private InvalidElectronicDocumentException(String message) {
        super(message);
    }

    public static InvalidElectronicDocumentException missing(String field) {
        return new InvalidElectronicDocumentException("El documento electrónico requiere " + field + ".");
    }

    public static InvalidElectronicDocumentException invalidCufe(String cufe) {
        return new InvalidElectronicDocumentException(
                "El CUFE debe tener 96 caracteres hexadecimales (SHA-384): '" + cufe + "'.");
    }

    public static InvalidElectronicDocumentException invalidUrl(String description, String url) {
        return new InvalidElectronicDocumentException(
                "La URL (" + description + ") debe ser absoluta y http(s): '" + url + "'.");
    }
}
