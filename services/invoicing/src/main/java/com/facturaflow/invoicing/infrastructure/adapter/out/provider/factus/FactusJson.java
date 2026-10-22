package com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code JsonMapper} propio del adaptador, independiente del de la API REST (que rechaza campos
 * desconocidos, ADR 0006): Factus agrega campos cuando quiere y no deben romper la integración.
 * Escribe en snake_case y omite los nulos (los opcionales no se envían).
 */
final class FactusJson {

    static final JsonMapper MAPPER = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    private FactusJson() {
    }
}
