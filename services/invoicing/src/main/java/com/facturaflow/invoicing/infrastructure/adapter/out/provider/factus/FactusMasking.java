package com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus;

/** Enmascara secretos para cuando haya que nombrarlos (p. ej. en un {@code toString}). */
final class FactusMasking {

    private FactusMasking() {
    }

    /** {@code eyJ0eX…[1034 caracteres]}: solo el prefijo, que no permite usar el token. */
    static String mask(String secret) {
        if (secret == null) {
            return "null";
        }
        return secret.length() <= 12 ? "***" : secret.substring(0, 6) + "…[" + secret.length() + " caracteres]";
    }
}
