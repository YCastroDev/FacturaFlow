package com.facturaflow.invoicing.application.port.in;

import com.facturaflow.invoicing.application.exception.CreditNoteNotFoundException;
import com.facturaflow.invoicing.application.exception.CreditNoteNotIssuedException;
import com.facturaflow.invoicing.application.exception.ElectronicInvoiceProviderUnavailableException;
import com.facturaflow.invoicing.application.port.out.InvoicePdf;
import java.util.UUID;

/** Representación gráfica (PDF) de una nota crédito emitida, obtenida del proveedor. */
public interface GetCreditNotePdfUseCase {

    /**
     * @throws CreditNoteNotFoundException si no existe
     * @throws CreditNoteNotIssuedException si no está ISSUED
     * @throws ElectronicInvoiceProviderUnavailableException si el proveedor no respondió
     */
    InvoicePdf pdf(UUID creditNoteId);
}
