package com.facturaflow.catalog.service;

import com.facturaflow.catalog.model.CustomerDocument;

/** 409: ya existe un cliente con ese tipo y número de documento, activo o no. */
public class DuplicateCustomerDocumentException extends RuntimeException {

    public DuplicateCustomerDocumentException(CustomerDocument document) {
        super("Ya existe un cliente con " + document.type().description() + " " + document.number()
                + " (puede estar inactivo).");
    }
}
