package com.facturaflow.sales.infrastructure.adapter.out.catalog;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code JsonMapper} propio del adaptador, independiente del de la API REST (que rechaza campos
 * desconocidos): lector tolerante, así un campo nuevo en catalog no rompe las ventas.
 */
final class CatalogJson {

    static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private CatalogJson() {
    }
}
