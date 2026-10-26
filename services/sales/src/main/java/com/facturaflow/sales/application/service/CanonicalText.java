package com.facturaflow.sales.application.service;

import com.facturaflow.sales.application.port.out.RequestHash;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Texto canónico de una petición, del que se calcula su huella (ADR 0012). Cada valor va con
 * su longitud delante ({@code len:valor;}) y {@code ~;} representa {@code null}: así ninguna
 * concatenación es ambigua. Copia del de invoicing (ADR 0012).
 */
final class CanonicalText {

    private final StringBuilder text = new StringBuilder();

    void add(String value) {
        if (value == null) {
            text.append("~;");
        } else {
            text.append(value.length()).append(':').append(value).append(';');
        }
    }

    @Override
    public String toString() {
        return text.toString();
    }

    static RequestHash sha256(String canonicalText) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonicalText.getBytes(StandardCharsets.UTF_8));
            return new RequestHash(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("La JVM no ofrece SHA-256", e);   // obligatorio en toda JVM
        }
    }
}
