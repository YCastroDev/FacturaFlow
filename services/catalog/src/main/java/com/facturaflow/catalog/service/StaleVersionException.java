package com.facturaflow.catalog.service;

/**
 * 412: el registro cambió desde que el cliente lo leyó (su {@code If-Match} ya no es la versión actual),
 * o lo cambió otra petición al mismo tiempo (bloqueo optimista de JPA). Se evita así la actualización
 * perdida: el cliente debe consultarlo de nuevo y decidir.
 */
public class StaleVersionException extends RuntimeException {

    public StaleVersionException(String subject) {
        super(subject + " cambió desde que lo consultó. Consúltelo de nuevo (GET) y reintente con el ETag "
                + "nuevo en If-Match.");
    }
}
