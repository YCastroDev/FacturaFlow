package com.facturaflow.sales.application.service;

import com.facturaflow.sales.application.port.in.ListSalesQuery;
import com.facturaflow.sales.application.port.in.ListSalesUseCase;
import com.facturaflow.sales.application.port.out.SalePage;
import com.facturaflow.sales.application.port.out.SaleRepository;
import java.util.Objects;

public final class ListSalesService implements ListSalesUseCase {

    private final SaleRepository repository;

    public ListSalesService(SaleRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public SalePage list(ListSalesQuery query) {
        return repository.findPage(query.status(), query.page(), query.size());
    }
}
