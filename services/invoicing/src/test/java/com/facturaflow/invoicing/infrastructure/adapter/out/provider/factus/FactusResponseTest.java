package com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FactusResponseTest {

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("dos respuestas con el mismo estado, Retry-After y cuerpo son iguales aunque sean arreglos distintos")
    void equalityComparesBody() {
        FactusResponse response = new FactusResponse(429, "3", bytes("{}"));

        assertThat(response).isEqualTo(new FactusResponse(429, "3", bytes("{}")))
                .hasSameHashCodeAs(new FactusResponse(429, "3", bytes("{}")))
                .isNotEqualTo(new FactusResponse(429, "3", bytes("[]")))
                .isNotEqualTo(new FactusResponse(429, null, bytes("{}")))
                .isNotEqualTo(new FactusResponse(503, "3", bytes("{}")));
        assertThat(new FactusResponse(204, null, null)).isEqualTo(new FactusResponse(204, null, null));
    }

    @Test
    @DisplayName("la representación en texto nunca muestra el cuerpo, solo su tamaño")
    void toStringHidesBody() {
        assertThat(new FactusResponse(200, null, bytes("{\"access_token\":\"secreto\"}")))
                .hasToString("FactusResponse[status=200, retryAfter=null, body=26 bytes]");
        assertThat(new FactusResponse(204, null, null))
                .hasToString("FactusResponse[status=204, retryAfter=null, body=null]");
    }
}
