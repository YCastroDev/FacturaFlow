package com.facturaflow.sales.application.port.out;

import com.facturaflow.sales.domain.model.CustomerSnapshot;
import java.util.Objects;

/** Un cliente tal como lo devolvió catalog: sus datos y si está activo. */
public record CatalogCustomer(CustomerSnapshot snapshot, boolean active) {

    public CatalogCustomer {
        Objects.requireNonNull(snapshot, "snapshot");
    }
}
