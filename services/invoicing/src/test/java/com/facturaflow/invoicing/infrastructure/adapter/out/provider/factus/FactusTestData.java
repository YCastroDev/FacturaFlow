package com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus;

import com.facturaflow.invoicing.domain.model.CreditBalance;
import com.facturaflow.invoicing.domain.model.CreditNote;
import com.facturaflow.invoicing.domain.model.CreditNoteId;
import com.facturaflow.invoicing.domain.model.CreditNoteReason;
import com.facturaflow.invoicing.domain.model.CreditedQuantity;
import com.facturaflow.invoicing.domain.model.CustomerSnapshot;
import com.facturaflow.invoicing.domain.model.DiscountRate;
import com.facturaflow.invoicing.domain.model.DocumentType;
import com.facturaflow.invoicing.domain.model.ElectronicDocument;
import com.facturaflow.invoicing.domain.model.FiscalProfile;
import com.facturaflow.invoicing.domain.model.IdentificationDocument;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.domain.model.InvoiceId;
import com.facturaflow.invoicing.domain.model.InvoiceLine;
import com.facturaflow.invoicing.domain.model.LineTax;
import com.facturaflow.invoicing.domain.model.Money;
import com.facturaflow.invoicing.domain.model.Municipality;
import com.facturaflow.invoicing.domain.model.PaymentMethod;
import com.facturaflow.invoicing.domain.model.PaymentTerms;
import com.facturaflow.invoicing.domain.model.PhoneNumber;
import com.facturaflow.invoicing.domain.model.Quantity;
import com.facturaflow.invoicing.domain.model.ReferenceCode;
import com.facturaflow.invoicing.domain.model.TaxType;
import com.facturaflow.invoicing.domain.model.UnitOfMeasure;
import com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusProperties.CircuitBreakerSettings;
import com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusProperties.RateLimiterSettings;
import com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusProperties.Resilience;
import com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusProperties.RetrySettings;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Facturas y notas crédito del dominio equivalentes a las que Factus aceptó en los spikes 0001 y 0002,
 * y sus respuestas reales.
 */
final class FactusTestData {

    static final LocalDate ISSUE_DATE = LocalDate.of(2026, 10, 21);
    static final String CLIENT_SECRET = "client-secret-de-prueba-123";
    static final String PASSWORD = "password-de-prueba-456";
    static final String CREDITED_BILL_NUMBER = "SETP990028959";
    /** Rango NC del sandbox (spike 0002). */
    static final int CREDIT_NOTE_RANGE = 390;
    static final LocalDate NOTE_DATE = LocalDate.of(2026, 10, 22);

    /**
     * Timeout de lectura de los tests que NO prueban timeouts: holgado, para que un runner lento no convierta
     * una respuesta normal en un timeout. Solo los que prueban el timeout usan {@link #SHORT_READ_TIMEOUT}
     * frente a una respuesta {@link #SLOW_RESPONSE}, con segundos de margen hacia los dos lados.
     */
    static final Duration GENEROUS_READ_TIMEOUT = Duration.ofSeconds(10);
    static final Duration SHORT_READ_TIMEOUT = Duration.ofSeconds(1);
    static final Duration SLOW_RESPONSE = Duration.ofSeconds(5);

    private FactusTestData() {
    }

    /** Contado con IVA: 2 x 85.000 con 10 % de descuento e IVA 19 % = 182.070,00 (payload cash-nit-iva). */
    static Invoice cashInvoice(UUID id) {
        CustomerSnapshot customer = CustomerSnapshot.company(IdentificationDocument.nit("890903938", 8),
                        "Cliente Ejemplo S.A.S.", FiscalProfile.notResponsible(), "facturacion@cliente.example")
                .withLocation(new Municipality("11001"), "Calle 1 # 2-3", new PhoneNumber("3001234567"));
        InvoiceLine line = new InvoiceLine("SKU-001", "Teclado inalámbrico", Quantity.of("2"), Money.of("85000.00"),
                DiscountRate.of("10"), List.of(LineTax.of(TaxType.IVA, "19")), UnitOfMeasure.UNIT, false);
        return Invoice.create(new InvoiceId(id), new ReferenceCode("VENTA-CASH-1"), customer, List.of(line),
                PaymentMethod.CASH, PaymentTerms.cash(), ISSUE_DATE, "Spike FacturaFlow: contado con IVA");
    }

    /**
     * Crédito: persona natural, una línea con descuento, IVA + INC y otra excluida de IVA = 147.475,01
     * (payload credit-cc-discount-iva-inc-excluded).
     */
    static Invoice creditInvoice(UUID id) {
        CustomerSnapshot customer = CustomerSnapshot.person(
                        IdentificationDocument.of(DocumentType.CITIZENSHIP_CARD, "1020304050"), "Ana María Gómez",
                        FiscalProfile.notResponsible(),
                        "ana.gomez@correo.example")
                .withLocation(new Municipality("05001"), null, null);
        InvoiceLine rounding = new InvoiceLine("SKU-RND", "Producto con centavos", Quantity.of("3"),
                Money.of("33333.33"), DiscountRate.of("7.5"),
                List.of(LineTax.of(TaxType.IVA, "19"), LineTax.of(TaxType.INC, "8")), UnitOfMeasure.UNIT, false);
        InvoiceLine excluded = new InvoiceLine("SKU-EXC", "Producto excluido de IVA", Quantity.of("1.5"),
                Money.of("20000.01"), DiscountRate.NONE, List.of(), UnitOfMeasure.UNIT, true);
        return Invoice.create(new InvoiceId(id), new ReferenceCode("VENTA-CRED-1"), customer,
                List.of(rounding, excluded), PaymentMethod.BANK_TRANSFER,
                PaymentTerms.credit(LocalDate.of(2026, 11, 20)), ISSUE_DATE,
                "Spike FacturaFlow: crédito, descuento, IVA + INC y línea excluida");
    }

    /** {@link #creditInvoice} ya emitida con el número y el CUFE reales de {@code SETP990028959} (spike 0002). */
    static Invoice issuedCreditInvoice(UUID id) {
        Invoice invoice = creditInvoice(id);
        invoice.markAsIssued(new ElectronicDocument(CREDITED_BILL_NUMBER,
                "3404fc52d61f1f64d31156d5a0155be46086a2478895be6722fc99408404c10e61d8a16feaebcd326750ba581f3e866a",
                URI.create("https://catalogo-vpfe-hab.dian.gov.co/document/searchqr?documentkey=3404fc52"),
                URI.create("https://app-sandbox.factus.com.co/documents/bills/0000test0000credit"),
                Instant.parse("2026-10-21T18:33:18Z"), Money.of("147475.01")));
        return invoice;
    }

    /** Devolución de 1 de las 3 unidades de SKU-RND: la nota {@code NC1661} del spike 0002 (39.158,33). */
    static CreditNote partialReturn(UUID id, Invoice issuedInvoice) {
        return CreditNote.create(new CreditNoteId(id), new ReferenceCode("DEV-1"),
                CreditBalance.of(issuedInvoice, List.of()), CreditNoteReason.PARTIAL_RETURN,
                List.of(new CreditedQuantity(1, Quantity.of("1"))), NOTE_DATE, "Devolución de 1 unidad");
    }

    /** Anulación de toda la factura: la nota {@code NC1663} del spike 0002 (147.475,01). */
    static CreditNote annulment(UUID id, Invoice issuedInvoice) {
        return CreditNote.create(new CreditNoteId(id), new ReferenceCode("ANU-1"),
                CreditBalance.of(issuedInvoice, List.of()), CreditNoteReason.TOTAL_ANNULMENT, List.of(), NOTE_DATE,
                null);
    }

    /**
     * Un solo intento y un circuito que no se abre: para los tests que prueban la TABLA de resultados
     * (cada respuesta de Factus, interpretada una vez). La resiliencia se prueba en {@code FactusResilienceTest}.
     */
    static Resilience singleAttempt() {
        return new Resilience(new RetrySettings(1, Duration.ofMillis(1), 1.0, 0.0, Duration.ZERO),
                new CircuitBreakerSettings(100, 100, 100, Duration.ofSeconds(1), 1),
                new RateLimiterSettings(1000, Duration.ofSeconds(1), Duration.ZERO));
    }

    /** Los mismos valores que {@code application.yml} (ADR 0010). */
    static Resilience productionResilience() {
        return new Resilience(new RetrySettings(3, Duration.ofMillis(500), 2.0, 0.5, Duration.ofSeconds(5)),
                new CircuitBreakerSettings(10, 5, 50, Duration.ofSeconds(30), 2),
                new RateLimiterSettings(10, Duration.ofSeconds(6), Duration.ofSeconds(2)));
    }

    static FactusProperties properties(String baseUrl) {
        return properties(baseUrl, false, null, GENEROUS_READ_TIMEOUT);
    }

    static FactusProperties properties(String baseUrl, boolean sendEmail, Integer numberingRangeId,
                                       Duration readTimeout) {
        return properties(baseUrl, sendEmail, numberingRangeId, readTimeout, singleAttempt());
    }

    static FactusProperties properties(String baseUrl, Duration readTimeout, Resilience resilience) {
        return properties(baseUrl, false, null, readTimeout, resilience);
    }

    private static FactusProperties properties(String baseUrl, boolean sendEmail, Integer numberingRangeId,
                                               Duration readTimeout, Resilience resilience) {
        return new FactusProperties(URI.create(baseUrl), "client-id-de-prueba", CLIENT_SECRET,
                "usuario@empresa.example", PASSWORD, sendEmail, numberingRangeId, CREDIT_NOTE_RANGE,
                Duration.ofSeconds(2), readTimeout, Duration.ofSeconds(60), "FacturaFlow-Test/1.0", resilience);
    }

    /** Un archivo de {@code src/test/resources/factus/} con {@code {{REFERENCE_CODE}}} reemplazado. */
    static String fixture(String name, UUID referenceCode) {
        return fixture(name).replace("{{REFERENCE_CODE}}", referenceCode.toString());
    }

    static String fixture(String name) {
        try (InputStream in = FactusTestData.class.getResourceAsStream("/factus/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("No existe el fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
