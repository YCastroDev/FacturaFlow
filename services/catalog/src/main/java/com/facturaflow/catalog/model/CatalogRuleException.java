package com.facturaflow.catalog.model;

import java.math.BigDecimal;

/**
 * Un dato maestro incumple una regla del catálogo (422). El mensaje está escrito para personas y se
 * devuelve tal cual en el Problem Details; nunca contiene datos que no vinieran en la petición.
 */
public class CatalogRuleException extends RuntimeException {

    public CatalogRuleException(String message) {
        super(message);
    }

    static CatalogRuleException missing(String field) {
        return new CatalogRuleException("Falta " + field + ".");
    }

    static CatalogRuleException tooLong(String field, int maxLength) {
        return new CatalogRuleException("El campo " + field + " admite máximo " + maxLength + " caracteres.");
    }

    static CatalogRuleException immutable(String field) {
        return new CatalogRuleException("No se puede cambiar " + field + ": identifica el registro en ventas y "
                + "facturas. Desactívelo y cree uno nuevo.");
    }

    static CatalogRuleException percentage(String field, BigDecimal value) {
        return new CatalogRuleException("La tarifa de " + field + " debe estar entre 0 y 100 y tener máximo 2 "
                + "decimales (recibido: " + value.toPlainString() + ").");
    }
}
