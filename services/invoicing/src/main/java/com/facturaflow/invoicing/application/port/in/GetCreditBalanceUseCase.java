package com.facturaflow.invoicing.application.port.in;

import com.facturaflow.invoicing.application.exception.InvoiceNotFoundException;
import com.facturaflow.invoicing.domain.model.CreditBalance;
import java.util.UUID;

/** Saldo acreditable de una factura: lo acreditado y lo que queda, por línea y en dinero (ADR 0009). */
public interface GetCreditBalanceUseCase {

    /** @throws InvoiceNotFoundException si la factura no existe */
    CreditBalance forInvoice(UUID invoiceId);
}
