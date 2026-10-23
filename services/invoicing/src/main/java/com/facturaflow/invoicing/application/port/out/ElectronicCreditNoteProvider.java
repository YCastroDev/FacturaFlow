package com.facturaflow.invoicing.application.port.out;

import com.facturaflow.invoicing.application.exception.ElectronicInvoiceProviderUnavailableException;
import com.facturaflow.invoicing.domain.model.CreditNote;
import com.facturaflow.invoicing.domain.model.Invoice;

/**
 * Puerto de salida hacia el proveedor tecnológico para las notas crédito (ADR 0009). Es un puerto
 * aparte de {@link ElectronicInvoiceProvider} (segregación de interfaces): emitir facturas no
 * necesita saber de notas. El mismo adaptador implementa los dos y comparte cliente y token.
 */
public interface ElectronicCreditNoteProvider {

    /**
     * Solicita la emisión de una nota PENDING. No modifica nada: el caso de uso aplica el resultado.
     *
     * @param creditedInvoice la factura ISSUED que acredita la nota; de ella salen el cliente y
     *                        la referencia al documento original
     * @throws ElectronicInvoiceProviderUnavailableException si el proveedor no respondió; se puede reintentar
     */
    IssuanceResult issue(CreditNote creditNote, Invoice creditedInvoice);

    /**
     * Descarga la representación gráfica (PDF) de una nota ISSUED. No se guarda.
     *
     * @throws ElectronicInvoiceProviderUnavailableException si el proveedor no respondió o
     *         entregó algo que no es un PDF; se puede reintentar
     */
    InvoicePdf downloadPdf(CreditNote issuedCreditNote);
}
