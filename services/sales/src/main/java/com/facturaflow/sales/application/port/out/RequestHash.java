package com.facturaflow.sales.application.port.out;

import java.util.regex.Pattern;

/**
 * Huella SHA-256 (64 caracteres hexadecimales en minúscula) de una petición de creación
 * normalizada. Permite saber si dos peticiones con el mismo código de referencia piden
 * la misma venta (ADR 0012). No es un dato de negocio: por eso no vive en el dominio.
 */
public record RequestHash(String value) {

    private static final Pattern FORMAT = Pattern.compile("[0-9a-f]{64}");

    public RequestHash {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Un RequestHash son 64 caracteres hexadecimales en minúscula: " + value);
        }
    }
}
