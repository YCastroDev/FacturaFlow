package com.facturaflow.sales.application.exception;

/** 404: no existe una venta con ese id o código de referencia. */
public class SaleNotFoundException extends RuntimeException {

    public SaleNotFoundException(String message) {
        super(message);
    }
}
