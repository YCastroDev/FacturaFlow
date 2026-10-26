package com.facturaflow.sales.infrastructure.adapter.in.messaging;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** {@code JsonMapper} propio del consumidor: lector TOLERANTE a campos desconocidos ({@code docs/events/README.md}). */
final class InvoiceResultJson {

    static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private InvoiceResultJson() {
    }
}
