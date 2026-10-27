package com.facturaflow.sales.infrastructure.adapter.out.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CatalogResponseTest {

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("dos respuestas con el mismo estado y cuerpo son iguales aunque sean arreglos distintos")
    void equalityComparesBody() {
        CatalogResponse response = new CatalogResponse(200, bytes("{}"));

        assertThat(response).isEqualTo(new CatalogResponse(200, bytes("{}")))
                .hasSameHashCodeAs(new CatalogResponse(200, bytes("{}")))
                .isNotEqualTo(new CatalogResponse(200, bytes("[]")))
                .isNotEqualTo(new CatalogResponse(404, bytes("{}")));
        assertThat(new CatalogResponse(204, null)).isEqualTo(new CatalogResponse(204, null));
    }

    @Test
    @DisplayName("la representación en texto nunca muestra el cuerpo, solo su tamaño")
    void toStringHidesBody() {
        assertThat(new CatalogResponse(200, bytes("{\"names\":\"Ana\"}")))
                .hasToString("CatalogResponse[status=200, body=15 bytes]");
        assertThat(new CatalogResponse(204, null)).hasToString("CatalogResponse[status=204, body=null]");
    }
}
