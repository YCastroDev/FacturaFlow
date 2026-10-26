package com.facturaflow.sales.domain.exception;

import java.math.BigDecimal;

public final class InvalidSaleLineException extends DomainException {

    private InvalidSaleLineException(String message) {
        super(message);
    }

    public static InvalidSaleLineException missing(String field) {
        return new InvalidSaleLineException("La línea de venta requiere " + field + ".");
    }

    public static InvalidSaleLineException tooLong(String field, int maxLength) {
        return new InvalidSaleLineException("El " + field + " de la línea admite máximo " + maxLength
                + " caracteres.");
    }

    public static InvalidSaleLineException unitPriceNotPositive(String code, BigDecimal unitPrice) {
        return new InvalidSaleLineException("El precio unitario de la línea '" + code
                + "' debe ser mayor que cero: " + unitPrice.toPlainString() + ".");
    }

    public static InvalidSaleLineException excludedWithIva(String code) {
        return new InvalidSaleLineException("La línea '" + code
                + "' está excluida de IVA y no puede tener un impuesto de tipo IVA.");
    }

    public static InvalidSaleLineException duplicateTax(String code, String taxType) {
        return new InvalidSaleLineException("La línea '" + code + "' tiene el impuesto " + taxType
                + " más de una vez.");
    }
}
