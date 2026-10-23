package com.facturaflow.catalog.web;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lectura del header {@code If-Match} (RFC 9110, sección 13.1.1): el ETag que el cliente recibió en el
 * header {@code ETag} de un GET, POST o PUT anterior, que es la versión del registro entre comillas
 * ({@code "3"}).
 *
 * <p>Se acepta un único ETag fuerte; por comodidad en Swagger también el número sin comillas. No se
 * aceptan {@code *} (actualizaría sin comprobar nada, justo lo que el header evita), listas de ETags ni
 * ETags débiles ({@code W/"3"}, que If-Match no admite): responden 400 con una explicación.
 */
final class IfMatch {

    private static final Pattern VERSION = Pattern.compile("\"?([0-9]{1,18})\"?");

    private IfMatch() {
    }

    /** Para PUT: sin el header, 428 (Precondition Required, RFC 6585). */
    static long required(String header) {
        if (header == null || header.isBlank()) {
            throw new PreconditionRequiredException();
        }
        return parse(header);
    }

    /** Para activar y desactivar: el header es opcional. */
    static Long optional(String header) {
        return header == null || header.isBlank() ? null : parse(header);
    }

    /** El valor del header {@code ETag} para una versión: entre comillas. */
    static String etag(long version) {
        return "\"" + version + "\"";
    }

    private static long parse(String header) {
        String value = header.strip();
        Matcher matcher = VERSION.matcher(value);
        boolean balancedQuotes = value.startsWith("\"") == value.endsWith("\"");
        if (!matcher.matches() || !balancedQuotes) {
            throw new InvalidIfMatchException();
        }
        return Long.parseLong(matcher.group(1));
    }
}
