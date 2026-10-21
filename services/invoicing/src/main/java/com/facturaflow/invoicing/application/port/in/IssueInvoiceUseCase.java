package com.facturaflow.invoicing.application.port.in;

import com.facturaflow.invoicing.application.exception.ElectronicInvoiceProviderUnavailableException;
import com.facturaflow.invoicing.application.exception.InvoiceAlreadyRejectedException;
import com.facturaflow.invoicing.application.exception.InvoiceConcurrentModificationException;
import com.facturaflow.invoicing.application.exception.InvoiceNotFoundException;
import com.facturaflow.invoicing.domain.model.Invoice;

/**
 * Emite una factura PENDING ante la DIAN a través del proveedor. Devuelve la factura ISSUED
 * o REJECTED: un rechazo es un resultado de negocio válido, no un error. Emitir una factura
 * ya ISSUED la devuelve sin volver a llamar al proveedor.
 */
public interface IssueInvoiceUseCase {

    /**
     * @throws InvoiceNotFoundException si no existe
     * @throws InvoiceAlreadyRejectedException si ya fue rechazada
     * @throws ElectronicInvoiceProviderUnavailableException si el proveedor falló; la factura sigue PENDING
     * @throws InvoiceConcurrentModificationException si otra emisión simultánea terminó distinto
     */
    Invoice issue(IssueInvoiceCommand command);
}
