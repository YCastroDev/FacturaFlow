package com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus;

import com.facturaflow.invoicing.application.exception.ElectronicInvoiceProviderUnavailableException;
import com.facturaflow.invoicing.application.exception.ElectronicInvoiceProviderUnavailableException.Document;
import com.facturaflow.invoicing.application.exception.ElectronicInvoiceProviderUnavailableException.Reason;
import com.facturaflow.invoicing.application.port.out.ElectronicCreditNoteProvider;
import com.facturaflow.invoicing.application.port.out.ElectronicInvoiceProvider;
import com.facturaflow.invoicing.application.port.out.InvoicePdf;
import com.facturaflow.invoicing.application.port.out.IssuanceResult;
import com.facturaflow.invoicing.domain.model.CreditNote;
import com.facturaflow.invoicing.domain.model.ElectronicDocument;
import com.facturaflow.invoicing.domain.model.Invoice;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.UUID;
import tools.jackson.core.JacksonException;

/**
 * Adaptador de los puertos {@link ElectronicInvoiceProvider} y {@link ElectronicCreditNoteProvider}
 * hacia Factus (ADR 0008). Orquesta el Anti-Corruption Layer: traduce el documento
 * ({@link FactusBillTranslator}, {@link FactusCreditNoteTranslator}), lo envía autenticado
 * ({@link FactusClient}, con un solo {@link TokenProvider} para los dos) e interpreta la respuesta con
 * la misma tabla ({@link FactusResponseInterpreter}). Toda falla
 * técnica sale como {@link ElectronicInvoiceProviderUnavailableException}; ningún tipo de Factus sale
 * de este paquete, con su motivo y su {@code Retry-After}. Nunca borra nada en Factus. Los reintentos,
 * el circuit breaker y el limitador viven en {@link FactusResilience}, debajo del cliente (ADR 0010).
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

    /** Arma el adaptador con registros propios (tests sin Spring). */
    static FactusElectronicInvoiceProvider create(FactusProperties properties, Clock clock) {
        return create(properties, clock, new FactusResilience(properties.resilience(), CircuitBreakerRegistry.ofDefaults(),
                RetryRegistry.ofDefaults(), RateLimiterRegistry.ofDefaults(), new SimpleMeterRegistry()));
    }

    /** Arma el adaptador completo; la configuración de Spring le pasa los registros de Resilience4j. */
    static FactusElectronicInvoiceProvider create(FactusProperties properties, Clock clock,
                                                  FactusResilience resilience) {
        FactusHttp http = new FactusHttp(properties, resilience);
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
            throw unavailable(Document.INVOICE, invoice.id().value(), e);
        }
    }

    @Override
    public InvoicePdf downloadPdf(Invoice issuedInvoice) {
        String number = issuedInvoice.electronicDocument().map(ElectronicDocument::number)
                .orElseThrow(() -> new IllegalStateException("La factura " + issuedInvoice.id() + " no está emitida"));
        try {
            return interpreter.pdf(number, client.downloadPdf(number));
        } catch (FactusTechnicalException e) {
            throw unavailable(Document.INVOICE, issuedInvoice.id().value(), e);
        }
    }

    @Override
    public IssuanceResult issue(CreditNote creditNote, Invoice creditedInvoice) {
        try {
            byte[] json = FactusJson.MAPPER.writeValueAsBytes(creditNoteTranslator.toCreditNote(creditNote,
                    creditedInvoice));
            return interpreter.creditNoteIssuance(creditNote, creditedInvoice, client.validateCreditNote(json));
        } catch (FactusTechnicalException | JacksonException e) {
            throw unavailable(Document.CREDIT_NOTE, creditNote.id().value(), e);
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
            throw unavailable(Document.CREDIT_NOTE, issuedCreditNote.id().value(), e);
        }
    }

    /** Traduce la falla técnica conservando el motivo y el {@code Retry-After} que verá el cliente. */
    private static ElectronicInvoiceProviderUnavailableException unavailable(Document document, UUID id,
                                                                             RuntimeException error) {
        return error instanceof FactusTechnicalException technical
                ? ElectronicInvoiceProviderUnavailableException.of(document, id, technical.getMessage(), technical,
                        technical.reason(), technical.retryAfter())
                : ElectronicInvoiceProviderUnavailableException.of(document, id, error.getMessage(), error,
                        Reason.FAILURE, null);
    }
}
