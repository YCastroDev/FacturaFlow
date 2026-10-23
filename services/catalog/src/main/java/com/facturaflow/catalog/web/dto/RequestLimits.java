package com.facturaflow.catalog.web.dto;

/**
 * Topes ANTI-ABUSO de la API. No son reglas de negocio: son generosos a propósito y solo evitan
 * peticiones desmesuradas. Los límites reales (largo del código, de la dirección, etc.) los valida el
 * modelo y responden 422.
 */
public final class RequestLimits {

    public static final int MAX_TEXT = 1000;
    public static final int MAX_TAXES = 10;
    public static final int MAX_FISCAL_RESPONSIBILITIES = 10;
    public static final int MAX_SEARCH_TEXT = 100;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int MAX_PAGE = 10_000;

    private RequestLimits() {
    }
}
