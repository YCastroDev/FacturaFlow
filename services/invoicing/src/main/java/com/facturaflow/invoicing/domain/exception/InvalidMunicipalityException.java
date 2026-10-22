package com.facturaflow.invoicing.domain.exception;

public final class InvalidMunicipalityException extends DomainException {

    private InvalidMunicipalityException(String message) {
        super(message);
    }

    public static InvalidMunicipalityException invalidFormat(String code) {
        return new InvalidMunicipalityException("El código de municipio debe ser el código DIVIPOLA del DANE "
                + "(5 dígitos, p. ej. 11001 para Bogotá): '" + code + "'.");
    }

    public static InvalidMunicipalityException unknownDepartment(String code) {
        return new InvalidMunicipalityException("El código de municipio '" + code + "' no corresponde a ningún "
                + "departamento: los 2 primeros dígitos deben ser un código de departamento DIVIPOLA.");
    }
}
