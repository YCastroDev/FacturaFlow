package com.facturaflow.sales.infrastructure.adapter.out.catalog;

import java.util.Arrays;
import java.util.Objects;

/**
 * Respuesta HTTP de catalog sin interpretar: estado y cuerpo. La igualdad compara el contenido del
 * cuerpo; {@code toString} solo muestra su tamaño (los datos del cliente no van a los logs).
 */
record CatalogResponse(int status, byte[] body) {

    @Override
    public boolean equals(Object other) {
        return other instanceof CatalogResponse that && status == that.status && Arrays.equals(body, that.body);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, Arrays.hashCode(body));
    }

    @Override
    public String toString() {
        return "CatalogResponse[status=" + status + ", body=" + (body == null ? "null" : body.length + " bytes") + "]";
    }
}
