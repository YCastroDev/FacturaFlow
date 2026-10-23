package com.facturaflow.invoicing.application.port.in;

import com.facturaflow.invoicing.application.exception.CreditNoteNotFoundException;
import com.facturaflow.invoicing.application.exception.InvoiceNotFoundException;
import com.facturaflow.invoicing.domain.model.CreditNote;
import java.util.List;
import java.util.UUID;

/** Consulta de notas crédito. Las notas devueltas son de solo lectura para quien las recibe. */
public interface GetCreditNoteUseCase {

    /** @throws CreditNoteNotFoundException si no existe */
    CreditNote byId(UUID creditNoteId);

    /**
     * Todas las notas de la factura, en cualquier estado, en orden de creación.
     *
     * @throws InvoiceNotFoundException si la factura no existe
     */
    List<CreditNote> byInvoice(UUID invoiceId);
}
