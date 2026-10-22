package com.facturaflow.invoicing.domain.exception;

public final class InvalidCustomerException extends DomainException {

    private InvalidCustomerException(String message) {
        super(message);
    }

    public static InvalidCustomerException missingIdentification() {
        return new InvalidCustomerException("El documento de identificación del cliente es obligatorio.");
    }

    public static InvalidCustomerException missingPersonType() {
        return new InvalidCustomerException("El tipo de persona del cliente (natural o jurídica) es obligatorio.");
    }

    public static InvalidCustomerException companyNameRequired() {
        return new InvalidCustomerException("Una persona jurídica requiere razón social.");
    }

    public static InvalidCustomerException namesNotAllowedForLegalEntity() {
        return new InvalidCustomerException(
                "Una persona jurídica se identifica por su razón social: no debe tener nombres de persona.");
    }

    public static InvalidCustomerException namesRequired() {
        return new InvalidCustomerException("Una persona natural requiere nombres.");
    }

    public static InvalidCustomerException companyNameNotAllowedForNaturalPerson() {
        return new InvalidCustomerException(
                "Una persona natural se identifica por sus nombres: no debe tener razón social.");
    }

    public static InvalidCustomerException missingFiscalProfile() {
        return new InvalidCustomerException(
                "El cliente requiere su situación tributaria (responsabilidad tributaria y responsabilidades fiscales).");
    }

    public static InvalidCustomerException missingTaxScheme() {
        return new InvalidCustomerException("La responsabilidad tributaria del cliente es obligatoria.");
    }

    public static InvalidCustomerException missingFiscalResponsibilities() {
        return new InvalidCustomerException(
                "El cliente debe tener al menos una responsabilidad fiscal (use NOT_RESPONSIBLE si no tiene ninguna).");
    }

    public static InvalidCustomerException notResponsibleCombined() {
        return new InvalidCustomerException(
                "La responsabilidad fiscal 'no responsable' no se puede combinar con otras responsabilidades.");
    }

    public static InvalidCustomerException invalidEmail(String email) {
        return new InvalidCustomerException("El email del cliente no tiene un formato válido: '" + email + "'.");
    }

    public static InvalidCustomerException addressTooLong(int length, int maxLength) {
        return new InvalidCustomerException("La dirección del cliente admite máximo " + maxLength
                + " caracteres (tiene " + length + ").");
    }

    public static InvalidCustomerException invalidPhone(String phone) {
        return new InvalidCustomerException("El teléfono del cliente debe tener de 7 a 15 dígitos, con un '+' "
                + "inicial opcional: '" + phone + "'.");
    }
}
