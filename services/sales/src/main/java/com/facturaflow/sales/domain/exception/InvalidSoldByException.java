package com.facturaflow.sales.domain.exception;

/** Los datos de quien hizo la venta faltan o no tienen una forma válida (ADR 0018, decisión 13). */
public final class InvalidSoldByException extends DomainException {

    private InvalidSoldByException(String message) {
        super(message);
    }

    public static InvalidSoldByException missing(String field) {
        return new InvalidSoldByException("La venta no trae " + field + " de quien la hizo.");
    }

    public static InvalidSoldByException tooLong(String field, int length, int max) {
        return new InvalidSoldByException("El " + field + " de quien hizo la venta tiene " + length
                + " caracteres; admite máximo " + max + ".");
    }
}
