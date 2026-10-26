package com.facturaflow.sales.infrastructure.adapter.out.catalog;

/**
 * Falla técnica de UN intento: sin respuesta (red, timeout) o un 5xx. {@code retryable} dice si vale la
 * pena repetirlo (ver {@link CatalogResilience}). El mensaje solo lleva la operación y la causa, nunca
 * cuerpos de respuesta.
 */
final class CatalogTechnicalException extends RuntimeException {

    private final boolean retryable;

    CatalogTechnicalException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    boolean retryable() {
        return retryable;
    }
}
