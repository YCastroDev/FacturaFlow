package com.facturaflow.sales.application.service;

import com.facturaflow.sales.application.exception.SaleNotFoundException;
import com.facturaflow.sales.application.port.in.GetSaleUseCase;
import com.facturaflow.sales.application.port.out.SaleRepository;
import com.facturaflow.sales.domain.model.ReferenceCode;
import com.facturaflow.sales.domain.model.Sale;
import com.facturaflow.sales.domain.model.SaleId;
import java.util.Objects;

public final class GetSaleService implements GetSaleUseCase {

    private final SaleRepository repository;

    public GetSaleService(SaleRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public Sale byId(SaleId id) {
        return repository.findById(id)
                .orElseThrow(() -> new SaleNotFoundException("No existe una venta con id " + id.value() + "."));
    }

    @Override
    public Sale byReferenceCode(String referenceCode) {
        ReferenceCode code = new ReferenceCode(referenceCode);
        return repository.findByReferenceCode(code).orElseThrow(() -> new SaleNotFoundException(
                "No existe una venta con el código de referencia '" + code.value() + "'."));
    }
}
