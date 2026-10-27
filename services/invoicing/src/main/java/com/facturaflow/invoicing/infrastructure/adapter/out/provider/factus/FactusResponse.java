package com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus;

import java.util.Arrays;
import java.util.Objects;

/**
 * Respuesta HTTP cruda de Factus: el cliente no decide qué significa, eso lo hace
 * {@link FactusResponseInterpreter}. {@code retryAfter} es el header {@code Retry-After}, si vino.
 * La igualdad compara el contenido del cuerpo; {@code toString} nunca lo muestra (solo su tamaño):
 * el cuerpo puede traer tokens o enlaces-capacidad (ADR 0008).
 */
record FactusResponse(int status, String retryAfter, byte[] body) {

    @Override
    public boolean equals(Object other) {
        return other instanceof FactusResponse that && status == that.status
                && Objects.equals(retryAfter, that.retryAfter) && Arrays.equals(body, that.body);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, retryAfter, Arrays.hashCode(body));
    }

    @Override
    public String toString() {
        return "FactusResponse[status=" + status + ", retryAfter=" + retryAfter + ", body="
                + (body == null ? "null" : body.length + " bytes") + "]";
    }
}
