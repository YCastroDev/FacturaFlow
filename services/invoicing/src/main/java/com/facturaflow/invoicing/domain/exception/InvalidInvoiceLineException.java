package com.facturaflow.invoicing.domain.exception;

import java.math.BigDecimal;

public final class InvalidInvoiceLineException extends DomainException {

    private InvalidInvoiceLineException(String message) {
        super(message);
    }

    public static InvalidInvoiceLineException missing(String field) {
        return new InvalidInvoiceLineException("La línea de factura requiere " + field + ".");
    }

    public static InvalidInvoiceLineException tooLong(String field, int maxLength) {
        return new InvalidInvoiceLineException("El " + field + " de la línea admite máximo " + maxLength
                + " caracteres.");
    }

    public static InvalidInvoiceLineException unitPriceNotPositive(String code, BigDecimal unitPrice) {
        return new InvalidInvoiceLineException("El precio unitario de la línea '" + code
                + "' debe ser mayor que cero: " + unitPrice.toPlainString() + ".");
    }

    public static InvalidInvoiceLineException excludedWithTax(String code, BigDecimal taxRate) {
        return new InvalidInvoiceLineException("La línea '" + code + "' está excluida de IVA pero tiene tarifa "
                + taxRate.toPlainString() + "%. Una línea excluida debe tener tarifa 0.");
    }
}
