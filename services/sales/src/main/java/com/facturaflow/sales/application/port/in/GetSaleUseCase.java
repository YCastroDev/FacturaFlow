package com.facturaflow.sales.application.port.in;

import com.facturaflow.sales.domain.model.Sale;
import com.facturaflow.sales.domain.model.SaleId;

public interface GetSaleUseCase {

    /** @throws com.facturaflow.sales.application.exception.SaleNotFoundException si no existe */
    Sale byId(SaleId id);

    /** @throws com.facturaflow.sales.application.exception.SaleNotFoundException si no existe */
    Sale byReferenceCode(String referenceCode);
}
