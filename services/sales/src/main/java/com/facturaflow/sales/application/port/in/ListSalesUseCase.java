package com.facturaflow.sales.application.port.in;

import com.facturaflow.sales.application.port.out.SalePage;

public interface ListSalesUseCase {

    /** De la más reciente a la más antigua. */
    SalePage list(ListSalesQuery query);
}
