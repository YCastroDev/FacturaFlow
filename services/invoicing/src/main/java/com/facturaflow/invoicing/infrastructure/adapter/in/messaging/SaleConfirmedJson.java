package com.facturaflow.invoicing.infrastructure.adapter.in.messaging;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code JsonMapper} propio del consumidor, independiente del de la API REST (que rechaza campos desconocidos):
 * lector TOLERANTE, como pide {@code docs/events/README.md}. Un campo que el contrato agregue de forma compatible
 * se ignora; un tipo equivocado (p. ej. un UUID o una fecha mal formados) sigue siendo un mensaje inválido.
 */
final class SaleConfirmedJson {

    static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private SaleConfirmedJson() {
    }
}
