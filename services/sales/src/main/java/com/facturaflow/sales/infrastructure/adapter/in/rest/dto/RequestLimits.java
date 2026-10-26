package com.facturaflow.sales.infrastructure.adapter.in.rest.dto;

/**
 * Topes ANTI-ABUSO de la API. No son reglas de negocio: son generosos y solo evitan peticiones
 * desmesuradas. Los límites reales (100 líneas, largo del código, observación) los valida el dominio (422).
 */
public final class RequestLimits {

    public static final int MAX_TEXT = 1000;
    public static final int MAX_LINES = 500;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int MAX_PAGE = 10_000;

    private RequestLimits() {
    }
}
