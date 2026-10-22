package com.facturaflow.invoicing.application.port.in;

import com.facturaflow.invoicing.application.exception.ElectronicInvoiceProviderUnavailableException;
import com.facturaflow.invoicing.application.exception.InvoiceNotFoundException;
import com.facturaflow.invoicing.application.exception.InvoiceNotIssuedException;
import com.facturaflow.invoicing.application.port.out.InvoicePdf;
import java.util.UUID;

/** Representación gráfica (PDF) de una factura emitida, obtenida del proveedor (ADR 0008). */
public interface GetInvoicePdfUseCase {

    /**
     * @throws InvoiceNotFoundException si no existe
     * @throws InvoiceNotIssuedException si no está ISSUED
     * @throws ElectronicInvoiceProviderUnavailableException si el proveedor no respondió
     */
    InvoicePdf pdf(UUID invoiceId);
}
