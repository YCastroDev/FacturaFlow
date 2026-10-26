package com.facturaflow.sales.application.port.out;

import com.facturaflow.sales.domain.model.LineTax;
import com.facturaflow.sales.domain.model.Money;
import com.facturaflow.sales.domain.model.UnitOfMeasure;
import java.util.List;
import java.util.UUID;

/** Un producto tal como lo devolvió catalog: precio neto, unidad, impuestos y si está activo. */
public record CatalogProduct(UUID id, String code, String name, Money unitPrice, UnitOfMeasure unit,
                             boolean ivaExcluded, List<LineTax> taxes, boolean active) {

    public CatalogProduct {
        taxes = List.copyOf(taxes);
    }
}
