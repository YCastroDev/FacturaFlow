package com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.facturaflow.invoicing.application.port.out.InvoicePdf;
import com.facturaflow.invoicing.application.port.out.IssuanceResult;
import com.facturaflow.invoicing.domain.model.CreditNote;
import com.facturaflow.invoicing.domain.model.ElectronicDocument;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.domain.model.InvoiceId;
import com.facturaflow.invoicing.domain.model.Money;
import com.facturaflow.invoicing.domain.model.PaymentTerms;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Contra el sandbox REAL de Factus (ADR 0008). Excluidos por defecto ({@code excludedGroups=sandbox}
 * en el pom); se ejecutan con las credenciales de {@code .env}, que Docker inyecta sin que nadie las lea:
 * <pre>
 *   MVN_ENV_FILE=.env ./scripts/mvn.sh invoicing test -Dgroups=sandbox -DexcludedGroups=none
 * </pre>
 * Cada ejecución crea DOS facturas reales y UNA nota crédito real (con {@code send_email=false}); el
 * rechazo no crea nada (un 422 no consume numeración). Nunca borra nada en Factus. El rango de notas
 * sale de {@code FACTUS_CREDIT_NOTE_NUMBERING_RANGE_ID}, o es el 390 (NC) del sandbox (spike 0002).
 */
@Tag("sandbox")
class FactusSandboxTest {

    private static FactusElectronicInvoiceProvider provider;

    @BeforeAll
    static void connect() {
        assumeTrue(Stream.of("FACTUS_CLIENT_ID", "FACTUS_CLIENT_SECRET", "FACTUS_USERNAME", "FACTUS_PASSWORD")
                .allMatch(name -> System.getenv(name) != null && !System.getenv(name).isBlank()),
                "Faltan las variables FACTUS_* (ver la clase para el comando)");
        String baseUrl = System.getenv().getOrDefault("FACTUS_BASE_URL", "https://api-sandbox.factus.com.co");
        assumeTrue(baseUrl.contains("sandbox"), "Estos tests solo corren contra el sandbox");
        provider = FactusElectronicInvoiceProvider.create(new FactusProperties(URI.create(baseUrl),
                System.getenv("FACTUS_CLIENT_ID"), System.getenv("FACTUS_CLIENT_SECRET"),
                System.getenv("FACTUS_USERNAME"), System.getenv("FACTUS_PASSWORD"), false, null, creditNoteRange(),
                Duration.ofSeconds(3), Duration.ofSeconds(10), Duration.ofSeconds(60), "FacturaFlow-Invoicing/0.0.1",
                FactusTestData.productionResilience()),
                Clock.systemUTC());
    }

    @Test
    @DisplayName("emite una factura real con el UUID (36 caracteres) como reference_code, la reenvía y baja su PDF")
    void issuesResendsAndDownloadsPdf() {
        Invoice invoice = FactusTestData.cashInvoice(UUID.randomUUID());

        IssuanceResult first = provider.issue(invoice);

        assertThat(first).isInstanceOf(IssuanceResult.Issued.class);
        ElectronicDocument document = ((IssuanceResult.Issued) first).document();
        assertThat(document.uniqueCode()).hasSize(96);
        assertThat(document.officialTotal()).isEqualTo(invoice.total());

        // Idempotencia de Factus: el mismo reference_code devuelve el mismo documento (no crea otro).
        IssuanceResult again = provider.issue(invoice);
        assertThat(((IssuanceResult.Issued) again).document().number()).isEqualTo(document.number());

        invoice.markAsIssued(document);
        InvoicePdf pdf = provider.downloadPdf(invoice);
        assertThat(new String(pdf.content(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    @DisplayName("un vencimiento ya pasado es un rechazo de negocio (422) con un motivo legible")
    void pastDueDateIsRejected() {
        Invoice valid = FactusTestData.creditInvoice(UUID.randomUUID());
        LocalDate past = LocalDate.now(FactusResponseInterpreter.FACTUS_ZONE).minusDays(10);
        Invoice pastDue = Invoice.create(new InvoiceId(UUID.randomUUID()), valid.referenceCode(), valid.customer(),
                valid.lines(), valid.paymentMethod(), PaymentTerms.credit(past.plusDays(1)), past,
                valid.observation().orElse(null));

        IssuanceResult result = provider.issue(pastDue);

        assertThat(result).isInstanceOfSatisfying(IssuanceResult.Rejected.class,
                rejected -> assertThat(rejected.reason()).contains("due_date"));
    }

    @Test
    @DisplayName("acredita una factura real con una devolución parcial: CUDE, bill.number, reenvío idempotente y PDF")
    void issuesPartialReturnOnRealInvoice() {
        Invoice invoice = FactusTestData.creditInvoice(UUID.randomUUID());
        IssuanceResult issuedInvoice = provider.issue(invoice);
        assertThat(issuedInvoice).isInstanceOf(IssuanceResult.Issued.class);
        invoice.markAsIssued(((IssuanceResult.Issued) issuedInvoice).document());
        CreditNote note = FactusTestData.partialReturn(UUID.randomUUID(), invoice);

        IssuanceResult first = provider.issue(note, invoice);

        assertThat(first).isInstanceOf(IssuanceResult.Issued.class);
        ElectronicDocument document = ((IssuanceResult.Issued) first).document();
        assertThat(document.uniqueCode()).hasSize(96);
        assertThat(document.officialTotal()).isEqualTo(note.total()).isEqualTo(Money.of("39158.33"));

        // Idempotencia ciega también en notas: el mismo reference_code devuelve la misma nota.
        IssuanceResult again = provider.issue(note, invoice);
        assertThat(((IssuanceResult.Issued) again).document().number()).isEqualTo(document.number());

        note.markAsIssued(document);
        InvoicePdf pdf = provider.downloadPdf(note);
        assertThat(new String(pdf.content(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    private static int creditNoteRange() {
        String value = System.getenv("FACTUS_CREDIT_NOTE_NUMBERING_RANGE_ID");
        return value == null || value.isBlank() ? FactusTestData.CREDIT_NOTE_RANGE : Integer.parseInt(value.strip());
    }
}
