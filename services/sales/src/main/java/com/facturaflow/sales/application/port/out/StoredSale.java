package com.facturaflow.sales.application.port.out;

import com.facturaflow.sales.domain.model.Sale;
import java.util.Objects;

/** Una venta tal como está guardada, junto con la huella de la petición que la creó. */
public record StoredSale(Sale sale, RequestHash requestHash) {

    public StoredSale {
        Objects.requireNonNull(sale, "sale");
        Objects.requireNonNull(requestHash, "requestHash");
    }
}
