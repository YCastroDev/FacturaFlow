package com.facturaflow.sales.domain.model;

import com.facturaflow.sales.domain.exception.InvalidSoldByException;

/**
 * Quién hizo la venta (ADR 0018, decisión 13): una COPIA del usuario al momento de vender, como el snapshot del
 * cliente. {@code userId} es su identificador estable en el proveedor de identidad (no cambia si el usuario se
 * renombra); {@code username} es el nombre para mostrar, tal como era al vender. Sales no los interpreta: no asume
 * que el id sea un UUID ni consulta al proveedor.
 *
 * <p>Sin espacios a los lados, ninguno vacío y máximo {@link #MAX_LENGTH} caracteres cada uno (el tope de Keycloak).
 */
public record SoldBy(String userId, String username) {

    public static final int MAX_LENGTH = 255;

    public SoldBy {
        userId = requireText(userId, "el identificador de usuario");
        username = requireText(username, "el nombre de usuario");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw InvalidSoldByException.missing(field);
        }
        String stripped = value.strip();
        if (stripped.length() > MAX_LENGTH) {
            throw InvalidSoldByException.tooLong(field, stripped.length(), MAX_LENGTH);
        }
        return stripped;
    }
}
