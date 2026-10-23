package com.facturaflow.catalog.service;

/**
 * 409: otra petición modificó el registro al mismo tiempo, en una operación SIN {@code If-Match}
 * (activar o desactivar); con {@code If-Match} el mismo caso es un {@link StaleVersionException} (412).
 */
public class ConcurrentUpdateException extends RuntimeException {

    public ConcurrentUpdateException(String subject) {
        super(subject + " fue modificado por otra petición al mismo tiempo. Reintente.");
    }
}
