package com.facturaflow.sales.application.port.out;

import com.facturaflow.sales.domain.model.Sale;
import java.util.List;

/** Una página de ventas. */
public record SalePage(List<Sale> items, int page, int size, long totalItems, int totalPages) {

    public SalePage {
        items = List.copyOf(items);
    }
}
