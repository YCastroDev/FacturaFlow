package com.facturaflow.sales.application.exception;

/**
 * El INSERT chocó con el UNIQUE del código de referencia: otra petición creó la venta entre la consulta
 * y el guardado. El caso de uso la resuelve releyendo la ganadora; no debería llegar al cliente.
 */
public class DuplicateReferenceCodeException extends RuntimeException {

    private final String referenceCode;

    public DuplicateReferenceCodeException(String referenceCode, Throwable cause) {
        super("Código de referencia duplicado: " + referenceCode, cause);
        this.referenceCode = referenceCode;
    }

    public String referenceCode() {
        return referenceCode;
    }
}
