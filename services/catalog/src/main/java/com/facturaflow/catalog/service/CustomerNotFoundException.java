package com.facturaflow.catalog.service;

/** 404: no existe un cliente con ese id o documento. */
public class CustomerNotFoundException extends RuntimeException {

    public CustomerNotFoundException(String message) {
        super(message);
    }
}
