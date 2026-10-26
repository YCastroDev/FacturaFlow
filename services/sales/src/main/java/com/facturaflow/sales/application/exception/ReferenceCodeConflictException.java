package com.facturaflow.sales.application.exception;

/** 409: el código de referencia ya existe con una petición DISTINTA (no es un reintento). */
public class ReferenceCodeConflictException extends RuntimeException {

    public ReferenceCodeConflictException(String referenceCode) {
        super("Ya existe una venta con el código de referencia '" + referenceCode + "' y un contenido distinto. "
                + "Si es otra venta, use otro código.");
    }
}
