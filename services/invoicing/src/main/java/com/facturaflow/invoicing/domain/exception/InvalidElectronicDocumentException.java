package com.facturaflow.invoicing.domain.exception;

public final class InvalidElectronicDocumentException extends DomainException {

    private InvalidElectronicDocumentException(String message) {
        super(message);
    }

    public static InvalidElectronicDocumentException missing(String field) {
        return new InvalidElectronicDocumentException("El documento electrónico requiere " + field + ".");
    }

    /** El código único es el CUFE de una factura o el CUDE de una nota crédito (ADR 0009). */
    public static InvalidElectronicDocumentException invalidUniqueCode(String uniqueCode) {
        return new InvalidElectronicDocumentException(
                "El código único del documento (CUFE/CUDE) debe tener 96 caracteres hexadecimales (SHA-384): '"
                        + uniqueCode + "'.");
    }

    public static InvalidElectronicDocumentException invalidUrl(String description, String url) {
        return new InvalidElectronicDocumentException(
                "La URL (" + description + ") debe ser absoluta y http(s): '" + url + "'.");
    }
}
