package com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus;

/**
 * Respuesta HTTP cruda de Factus: el cliente no decide qué significa, eso lo hace
 * {@link FactusResponseInterpreter}. {@code retryAfter} es el header {@code Retry-After}, si vino.
 */
record FactusResponse(int status, String retryAfter, byte[] body) {
}
