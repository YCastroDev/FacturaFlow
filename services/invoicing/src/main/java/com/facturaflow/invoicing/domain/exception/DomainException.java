package com.facturaflow.invoicing.domain.exception;

/**
 * Base de todas las violaciones de reglas de negocio. Las subclases reciben
 * solo valores simples (String, UUID, números), nunca objetos de domain.model,
 * para que este paquete no dependa del modelo (sin ciclos).
 */
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }
}
