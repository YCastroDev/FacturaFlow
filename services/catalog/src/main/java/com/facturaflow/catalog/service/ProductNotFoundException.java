package com.facturaflow.catalog.service;

/** 404: no existe un producto con ese id o código. */
public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(String message) {
        super(message);
    }
}
