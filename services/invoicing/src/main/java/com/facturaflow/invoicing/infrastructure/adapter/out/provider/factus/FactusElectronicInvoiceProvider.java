package com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus;

import com.facturaflow.invoicing.application.exception.ElectronicInvoiceProviderUnavailableException;
import com.facturaflow.invoicing.application.port.out.ElectronicCreditNoteProvider;
import com.facturaflow.invoicing.application.port.out.ElectronicInvoiceProvider;
import com.facturaflow.invoicing.application.port.out.InvoicePdf;
import com.facturaflow.invoicing.application.port.out.IssuanceResult;
import com.facturaflow.invoicing.domain.model.CreditNote;
import com.facturaflow.invoicing.domain.model.ElectronicDocument;
import com.facturaflow.invoicing.domain.model.Invoice;
import java.time.Clock;
import tools.jackson.core.JacksonException;

/**
 * Adaptador de los puertos {@link ElectronicInvoiceProvider} y {@link ElectronicCreditNoteProvider}
 * hacia Factus (ADR 0008). Orquesta el Anti-Corruption Layer: traduce el documento
 * ({@link FactusBillTranslator}, {@link FactusCreditNoteTranslator}), lo envía autenticado
 * ({@link FactusClient}, con un solo {@link TokenProvider} para los dos) e interpreta la respuesta con
 * la misma tabla ({@link FactusResponseInterpreter}). Toda falla
 * técnica sale como {@link ElectronicInvoiceProviderUnavailableException}; ningún tipo de Factus sale
 * de este paquete. Nunca borra nada en Factus y no reintenta (más allá del 401): la resiliencia
 * llegará con Resilience4j.
 */
final class FactusElectronicInvoiceProvider implements ElectronicInvoiceProvider, ElectronicCreditNoteProvider {

    private final FactusBillTranslator translator;
    private final FactusCreditNoteTranslator creditNoteTranslator;
    private final FactusClient client;
    private final FactusResponseInterpreter interpreter;

    FactusElectronicInvoiceProvider(FactusBillTranslator translator, FactusCreditNoteTranslator creditNoteTranslator,
                                    FactusClient client, FactusResponseInterpreter interpreter) {
        this.translator = translator;
        this.creditNoteTranslator = creditNoteTranslator;
        this.client = client;
        this.interpreter = interpreter;
    }

    /** Arma el adaptador completo; lo usan la configuración de Spring y los tests (sin Spring). */
    static FactusElectronicInvoiceProvider create(FactusProperties properties, Clock clock) {
        FactusHttp http = new FactusHttp(properties);
        TokenProvider tokens = new InMemoryTokenProvider(new FactusTokenEndpoint(http, properties), clock,
                properties.tokenRefreshMargin());
        return new FactusElectronicInvoiceProvider(
                new FactusBillTranslator(properties.sendEmail(), properties.numberingRangeId()),
                new FactusCreditNoteTranslator(properties.sendEmail(), properties.creditNoteNumberingRangeId()),
                new FactusClient(http, tokens),
                new FactusResponseInterpreter());
    }

    @Override
    public IssuanceResult issue(Invoice invoice) {
        try {
            byte[] json = FactusJson.MAPPER.writeValueAsBytes(translator.toBill(invoice));
            return interpreter.issuance(invoice, client.validateBill(json));
        } catch (FactusTechnicalException | JacksonException e) {
            throw new ElectronicInvoiceProviderUnavailableException(invoice.id().value(), e.getMessage(), e);
        }
    }

    @Override
    public InvoicePdf downloadPdf(Invoice issuedInvoice) {
        String number = issuedInvoice.electronicDocument().map(ElectronicDocument::number)
                .orElseThrow(() -> new IllegalStateException("La factura " + issuedInvoice.id() + " no está emitida"));
        try {
            return interpreter.pdf(number, client.downloadPdf(number));
        } catch (FactusTechnicalException e) {
            throw new ElectronicInvoiceProviderUnavailableException(issuedInvoice.id().value(), e.getMessage(), e);
        }
    }

    @Override
    public IssuanceResult issue(CreditNote creditNote, Invoice creditedInvoice) {
        try {
            byte[] json = FactusJson.MAPPER.writeValueAsBytes(creditNoteTranslator.toCreditNote(creditNote,
                    creditedInvoice));
            return interpreter.creditNoteIssuance(creditNote, creditedInvoice, client.validateCreditNote(json));
        } catch (FactusTechnicalException | JacksonException e) {
            throw ElectronicInvoiceProviderUnavailableException.forCreditNote(creditNote.id().value(), e.getMessage(),
                    e);
        }
    }

    @Override
    public InvoicePdf downloadPdf(CreditNote issuedCreditNote) {
        String number = issuedCreditNote.electronicDocument().map(ElectronicDocument::number)
                .orElseThrow(() -> new IllegalStateException("La nota crédito " + issuedCreditNote.id()
                        + " no está emitida"));
        try {
            return interpreter.pdf(number, client.downloadCreditNotePdf(number));
        } catch (FactusTechnicalException e) {
            throw ElectronicInvoiceProviderUnavailableException.forCreditNote(issuedCreditNote.id().value(),
                    e.getMessage(), e);
        }
    }
}
