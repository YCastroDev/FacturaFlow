package com.facturaflow.catalog.service;

/** 409: ya existe un producto con ese código (sin distinguir mayúsculas), activo o no. */
public class DuplicateProductCodeException extends RuntimeException {

    public DuplicateProductCodeException(String code) {
        super("Ya existe un producto con el código '" + code + "' (sin distinguir mayúsculas; puede estar "
                + "inactivo).");
    }
}
