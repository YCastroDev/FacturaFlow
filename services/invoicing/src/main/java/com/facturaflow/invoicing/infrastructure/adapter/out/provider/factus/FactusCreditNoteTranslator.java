package com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus;

import com.facturaflow.invoicing.domain.model.CreditNote;
import com.facturaflow.invoicing.domain.model.CreditNoteLine;
import com.facturaflow.invoicing.domain.model.ElectronicDocument;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.domain.model.PaymentTerms;
import com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusDtos.CreditNoteRequest;
import com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusDtos.PaymentDetail;
import java.util.List;

/**
 * Traduce una {@link CreditNote} y su factura a la petición de {@code POST /v2/credit-notes/validate}
 * (spike 0002). Reutiliza el cliente y los ítems de {@link FactusBillTranslator}, así que una nota usa
 * exactamente los mismos códigos que la factura:
 * <ul>
 *   <li>{@code reference_code} es el {@code CreditNoteId} (UUID), por la misma idempotencia ciega que
 *       en facturas;</li>
 *   <li>el cliente es SIEMPRE el de la factura: Factus lo exige y no lo toma de ella (spike 0002, §7);</li>
 *   <li>cada ítem es la línea acreditada (la de la factura con la cantidad de la nota);</li>
 *   <li>{@code bill_number} es el número que la DIAN asignó a la factura;</li>
 *   <li>el pago es de contado, con el medio de la factura y el total de la nota (ADR 0009): la
 *       devolución no abre un plazo nuevo, y un vencimiento ya pasado de la factura a crédito haría
 *       fallar la validación.</li>
 * </ul>
 */
final class FactusCreditNoteTranslator {

    private final boolean sendEmail;
    private final int numberingRangeId;

    FactusCreditNoteTranslator(boolean sendEmail, int numberingRangeId) {
        this.sendEmail = sendEmail;
        this.numberingRangeId = numberingRangeId;
    }

    CreditNoteRequest toCreditNote(CreditNote creditNote, Invoice creditedInvoice) {
        String billNumber = creditedInvoice.electronicDocument().map(ElectronicDocument::number)
                .orElseThrow(() -> new IllegalStateException("La factura " + creditedInvoice.id()
                        + " de la nota crédito " + creditNote.id() + " no está emitida"));
        return new CreditNoteRequest(
                creditNote.id().value().toString(),
                FactusCodes.correctionConcept(creditNote.reason()),
                FactusCodes.CREDIT_NOTE_REFERENCING_INVOICE,
                billNumber,
                numberingRangeId,
                creditNote.observation().orElse(null),
                sendEmail,
                FactusBillTranslator.customer(creditedInvoice.customer()),
                creditNote.lines().stream().map(CreditNoteLine::creditedLine).map(FactusBillTranslator::item).toList(),
                List.of(new PaymentDetail(FactusCodes.paymentForm(PaymentTerms.cash()),
                        FactusCodes.paymentMethod(creditedInvoice.paymentMethod()), creditNote.total().amount(),
                        null)));
    }
}
