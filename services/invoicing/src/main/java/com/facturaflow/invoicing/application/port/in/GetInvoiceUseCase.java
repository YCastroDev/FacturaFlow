package com.facturaflow.invoicing.application.port.in;

import com.facturaflow.invoicing.application.exception.InvoiceNotFoundException;
import com.facturaflow.invoicing.domain.model.Invoice;
import java.util.UUID;

/** Consulta de facturas. La factura devuelta es de solo lectura para quien la recibe. */
public interface GetInvoiceUseCase {

    /** @throws InvoiceNotFoundException si no existe */
    Invoice byId(UUID invoiceId);

    /** @throws InvoiceNotFoundException si no existe */
    Invoice byReferenceCode(String referenceCode);
}
