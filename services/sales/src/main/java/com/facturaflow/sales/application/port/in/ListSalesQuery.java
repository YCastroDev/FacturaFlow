package com.facturaflow.sales.application.port.in;

import com.facturaflow.sales.domain.model.SaleStatus;

/**
 * Página pedida: número desde 0, tamaño y estado opcional ({@code null} = todos). Los límites los valida
 * la capa web.
 */
public record ListSalesQuery(SaleStatus status, int page, int size) {

    public ListSalesQuery {
        if (page < 0 || size < 1) {
            throw new IllegalArgumentException("página inválida: " + page + "/" + size);
        }
    }
}
