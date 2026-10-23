package com.facturaflow.invoicing.application.port.in;

import com.facturaflow.invoicing.application.exception.CreditNoteAlreadyRejectedException;
import com.facturaflow.invoicing.application.exception.CreditNoteConcurrentModificationException;
import com.facturaflow.invoicing.application.exception.CreditNoteNotFoundException;
import com.facturaflow.invoicing.application.exception.ElectronicInvoiceProviderUnavailableException;
import com.facturaflow.invoicing.domain.model.CreditNote;

/**
 * Emite una nota crédito PENDING ante la DIAN, con el mismo comportamiento que la factura: devuelve
 * la nota ISSUED o REJECTED (un rechazo es un resultado válido), y emitir una nota ya ISSUED la
 * devuelve sin volver a llamar al proveedor.
 */
public interface IssueCreditNoteUseCase {

    /**
     * @throws CreditNoteNotFoundException si no existe
     * @throws CreditNoteAlreadyRejectedException si ya fue rechazada
     * @throws ElectronicInvoiceProviderUnavailableException si el proveedor falló; la nota sigue PENDING
     * @throws CreditNoteConcurrentModificationException si otra emisión simultánea terminó distinto
     */
    CreditNote issue(IssueCreditNoteCommand command);
}
