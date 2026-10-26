package com.facturaflow.sales.domain.exception;

/**
 * Los datos del cliente que llegaron de catalog están incompletos. Sales solo comprueba su ESTRUCTURA:
 * las reglas fiscales las validó catalog al guardarlo e invoicing las revalida (ADR 0012, D4).
 */
public final class InvalidCustomerSnapshotException extends DomainException {

    private InvalidCustomerSnapshotException(String message) {
        super(message);
    }

    public static InvalidCustomerSnapshotException missing(String field) {
        return new InvalidCustomerSnapshotException("Los datos del cliente no traen " + field + ".");
    }
}
