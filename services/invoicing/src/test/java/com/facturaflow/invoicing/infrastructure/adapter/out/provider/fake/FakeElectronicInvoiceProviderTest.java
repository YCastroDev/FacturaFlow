package com.facturaflow.invoicing.infrastructure.adapter.out.provider.fake;

import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.ISSUE_DATE;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.customer;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.electronicDocument;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.invoicing.application.exception.ElectronicInvoiceProviderUnavailableException;
import com.facturaflow.invoicing.application.port.out.InvoicePdf;
import com.facturaflow.invoicing.application.port.out.IssuanceResult;
import com.facturaflow.invoicing.domain.model.ElectronicDocument;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.domain.model.InvoiceId;
import com.facturaflow.invoicing.domain.model.PaymentMethod;
import com.facturaflow.invoicing.domain.model.PaymentTerms;
import com.facturaflow.invoicing.domain.model.ReferenceCode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FakeElectronicInvoiceProviderTest {

    private static final Instant NOW = Instant.parse("2026-10-20T15:30:00Z");

    private final FakeElectronicInvoiceProvider provider =
            new FakeElectronicInvoiceProvider(Clock.fixed(NOW, ZoneId.of("America/Bogota")));

    private static Invoice invoice(String observation) {
        return Invoice.create(InvoiceId.generate(), new ReferenceCode("VENTA-7"), customer(),
                List.of(line("2", "50000.00", "10", "19")), PaymentMethod.CASH, PaymentTerms.cash(), ISSUE_DATE,
                observation);
    }

    @Test
    @DisplayName("sin palabras de simulación emite un documento con datos claramente falsos")
    void issuesFakeDocument() {
        Invoice invoice = invoice("Venta normal");

        IssuanceResult result = provider.issue(invoice);

        assertThat(result).isInstanceOf(IssuanceResult.Issued.class);
        ElectronicDocument document = ((IssuanceResult.Issued) result).document();
        assertThat(document.number()).isEqualTo("FAKE-VENTA-7");
        assertThat(document.cufe()).hasSize(96).startsWith(FakeElectronicInvoiceProvider.FAKE_CUFE_MARK);
        assertThat(document.qrUrl()).hasToString("https://fake-provider.invalid/qr/FAKE-VENTA-7");
        assertThat(document.publicUrl()).hasToString("https://fake-provider.invalid/invoices/FAKE-VENTA-7");
        assertThat(document.validatedAt()).isEqualTo(NOW);
        assertThat(document.officialTotal()).isEqualTo(invoice.total());
    }

    @Test
    @DisplayName("una factura sin observación también se emite")
    void issuesWithoutObservation() {
        assertThat(provider.issue(invoice(null))).isInstanceOf(IssuanceResult.Issued.class);
    }

    @Test
    @DisplayName("es determinista: la misma factura produce siempre el mismo CUFE, y otra factura uno distinto")
    void isDeterministic() {
        Invoice invoice = invoice(null);

        String first = ((IssuanceResult.Issued) provider.issue(invoice)).document().cufe();
        String again = ((IssuanceResult.Issued) provider.issue(invoice)).document().cufe();
        String other = ((IssuanceResult.Issued) provider.issue(invoice(null))).document().cufe();

        assertThat(again).isEqualTo(first);
        assertThat(other).isNotEqualTo(first);
    }

    @Test
    @DisplayName("con SIMULAR_RECHAZO en la observación la rechaza con un motivo")
    void simulatesRejection() {
        IssuanceResult result = provider.issue(invoice("Por favor SIMULAR_RECHAZO"));

        assertThat(result).isInstanceOfSatisfying(IssuanceResult.Rejected.class,
                rejected -> assertThat(rejected.reason()).contains("SIMULAR_RECHAZO"));
    }

    @Test
    @DisplayName("con SIMULAR_CAIDA en la observación lanza la falla técnica, aunque también pida rechazo")
    void simulatesOutage() {
        Invoice invoice = invoice("SIMULAR_CAIDA y SIMULAR_RECHAZO");

        assertThatThrownBy(() -> provider.issue(invoice))
                .isInstanceOf(ElectronicInvoiceProviderUnavailableException.class)
                .hasMessageContaining(invoice.id().toString());
    }

    @Test
    @DisplayName("el PDF de una factura emitida es un PDF válido marcado como falso, con el número como nombre")
    void downloadsMinimalFakePdf() {
        Invoice invoice = invoice(null);
        invoice.markAsIssued(((IssuanceResult.Issued) provider.issue(invoice)).document());

        InvoicePdf pdf = provider.downloadPdf(invoice);

        String content = new String(pdf.content(), StandardCharsets.US_ASCII);
        assertThat(pdf.fileName()).isEqualTo("FAKE-VENTA-7.pdf");
        assertThat(content).startsWith("%PDF-1.4\n").endsWith("%%EOF\n")
                .contains("(DOCUMENTO FALSO - SIN VALIDEZ)", "(Factura FAKE-VENTA-7)");
        assertXrefPointsToObjects(content);
    }

    @Test
    @DisplayName("con SIMULAR_CAIDA en la observación la descarga del PDF también es una falla técnica")
    void simulatesOutageOnPdf() {
        Invoice issued = invoice("SIMULAR_CAIDA");
        issued.markAsIssued(electronicDocument("1.00"));

        assertThatThrownBy(() -> provider.downloadPdf(issued))
                .isInstanceOf(ElectronicInvoiceProviderUnavailableException.class);
    }

    /** Cada entrada de la tabla xref debe apuntar exactamente al inicio de su objeto, y startxref a la tabla. */
    private static void assertXrefPointsToObjects(String pdf) {
        int xref = pdf.indexOf("xref\n");
        Matcher startXref = Pattern.compile("startxref\n(\\d+)\n").matcher(pdf);
        assertThat(startXref.find()).isTrue();
        assertThat(Integer.parseInt(startXref.group(1))).isEqualTo(xref);
        Matcher entries = Pattern.compile("(\\d{10}) 00000 n ").matcher(pdf.substring(xref));
        int object = 1;
        while (entries.find()) {
            assertThat(pdf.substring(Integer.parseInt(entries.group(1)))).startsWith(object + " 0 obj\n");
            object++;
        }
        assertThat(object - 1).isEqualTo(5);
    }
}
