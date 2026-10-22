package com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto;

/**
 * Topes ANTI-ABUSO de la API (ADR 0006, D6). No son reglas de negocio: son generosos a
 * propósito y solo evitan peticiones desmesuradas. Los límites reales (largo del código,
 * de la observación, etc.) los valida el dominio y responden 422.
 */
final class RequestLimits {

    static final int MAX_TEXT = 1000;
    static final int MAX_LINES = 500;
    static final int MAX_TAXES_PER_LINE = 10;
    static final int MAX_FISCAL_RESPONSIBILITIES = 10;

    private RequestLimits() {
    }
}
