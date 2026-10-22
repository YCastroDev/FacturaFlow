package com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus;

import com.facturaflow.invoicing.application.exception.ElectronicInvoiceProviderUnavailableException;
import com.facturaflow.invoicing.application.port.out.ElectronicInvoiceProvider;
import com.facturaflow.invoicing.application.port.out.InvoicePdf;
import com.facturaflow.invoicing.application.port.out.IssuanceResult;
import com.facturaflow.invoicing.domain.model.ElectronicDocument;
import com.facturaflow.invoicing.domain.model.Invoice;
import java.time.Clock;
import tools.jackson.core.JacksonException;

/**
 * Adaptador del puerto {@link ElectronicInvoiceProvider} hacia Factus (ADR 0008). Orquesta el
 * Anti-Corruption Layer: traduce la factura ({@link FactusBillTranslator}), la envía autenticada
 * ({@link FactusClient}) e interpreta la respuesta ({@link FactusResponseInterpreter}). Toda falla
 * técnica sale como {@link ElectronicInvoiceProviderUnavailableException}; ningún tipo de Factus sale
 * de este paquete. Nunca borra nada en Factus y no reintenta (más allá del 401): la resiliencia
 * llegará con Resilience4j.
 */
final class FactusElectronicInvoiceProvider implements ElectronicInvoiceProvider {

    private final FactusBillTranslator translator;
    private final FactusClient client;
    private final FactusResponseInterpreter interpreter;

    FactusElectronicInvoiceProvider(FactusBillTranslator translator, FactusClient client,
                                    FactusResponseInterpreter interpreter) {
        this.translator = translator;
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
}
