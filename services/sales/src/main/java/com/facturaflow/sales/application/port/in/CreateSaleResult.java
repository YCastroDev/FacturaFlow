package com.facturaflow.sales.application.port.in;

import com.facturaflow.sales.domain.model.Sale;
import java.util.Objects;

/** La venta y si se creó ahora (201) o ya existía con la misma petición (200, reintento idempotente). */
public record CreateSaleResult(Sale sale, boolean created) {

    public CreateSaleResult {
        Objects.requireNonNull(sale, "sale");
    }

    public static CreateSaleResult created(Sale sale) {
        return new CreateSaleResult(sale, true);
    }

    public static CreateSaleResult replayed(Sale sale) {
        return new CreateSaleResult(sale, false);
    }
}
