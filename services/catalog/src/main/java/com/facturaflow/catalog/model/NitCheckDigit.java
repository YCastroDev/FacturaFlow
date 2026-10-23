package com.facturaflow.catalog.model;

/**
 * Dígito de verificación del NIT según el algoritmo oficial de la DIAN (módulo 11): cada dígito, de
 * derecha a izquierda, se multiplica por un peso primo; si el residuo de la suma entre 11 es 0 o 1, ese
 * es el DV; si no, el DV es 11 - residuo.
 *
 * <p>COPIA DELIBERADA de {@code NitCheckDigit} de invoicing (ADR 0001, ADR 0011): los servicios no
 * comparten código. Los dos tests usan los mismos NIT de ejemplo; si uno cambia, revise el otro.
 */
public final class NitCheckDigit {

    private static final int[] WEIGHTS = {3, 7, 13, 17, 19, 23, 29, 37, 41, 43, 47, 53, 59, 67, 71};

    private NitCheckDigit() {
    }

    public static int calculate(String nit) {
        if (nit == null || nit.isEmpty() || nit.length() > WEIGHTS.length || !nit.chars().allMatch(Character::isDigit)) {
            throw new CatalogRuleException("El NIT debe tener entre 1 y " + WEIGHTS.length + " dígitos.");
        }
        int sum = 0;
        for (int i = 0; i < nit.length(); i++) {
            int digit = nit.charAt(nit.length() - 1 - i) - '0';
            sum += digit * WEIGHTS[i];
        }
        int remainder = sum % 11;
        return remainder < 2 ? remainder : 11 - remainder;
    }
}
