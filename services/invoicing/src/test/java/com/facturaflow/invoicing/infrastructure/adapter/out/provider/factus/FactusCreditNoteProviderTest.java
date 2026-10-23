package com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus;

import static com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusTestData.CREDITED_BILL_NUMBER;
import static com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusTestData.annulment;
import static com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusTestData.fixture;
import static com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusTestData.issuedCreditInvoice;
import static com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusTestData.partialReturn;
import static com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusTestData.properties;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.invoicing.application.exception.ElectronicInvoiceProviderUnavailableException;
import com.facturaflow.invoicing.application.port.out.InvoicePdf;
import com.facturaflow.invoicing.application.port.out.IssuanceResult;
import com.facturaflow.invoicing.domain.model.CreditNote;
import com.facturaflow.invoicing.domain.model.ElectronicDocument;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.domain.model.Money;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Notas crédito con el adaptador completo contra un Factus falso (WireMock) que responde con los cuerpos
 * reales del spike 0002. Mismo cliente, token e intérprete que las facturas
 * ({@link FactusElectronicInvoiceProviderTest} cubre el token, el 401 y las fallas de red, comunes a los dos).
 */
@ExtendWith(OutputCaptureExtension.class)
class FactusCreditNoteProviderTest {

    private static final String VALIDATE_PATH = FactusClient.CREDIT_NOTE_VALIDATE_PATH;
    private static final String NUMBER = "NC1661";

    @RegisterExtension
    static final WireMockExtension FACTUS = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private final UUID id = UUID.randomUUID();
    private final Invoice invoice = issuedCreditInvoice(UUID.randomUUID());
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-22T20:00:00Z"));
    private FactusElectronicInvoiceProvider provider;

    @BeforeEach
    void setUp() {
        provider = FactusElectronicInvoiceProvider.create(properties(FACTUS.baseUrl()), clock);
        FACTUS.stubFor(post(urlEqualTo(FactusTokenEndpoint.PATH)).willReturn(json(200,
                "{\"token_type\":\"Bearer\",\"expires_in\":3600,\"access_token\":\"access-nc-0123456789abcdef\","
                        + "\"refresh_token\":\"refresh-nc-0123456789abcdef\"}")));
    }

    private static ResponseDefinitionBuilder json(int status, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
    }

    private void respond(int status, String body) {
        FACTUS.stubFor(post(urlEqualTo(VALIDATE_PATH)).willReturn(json(status, body)));
    }

    private CreditNote partial() {
        return partialReturn(id, invoice);
    }

    private ElectronicDocument issuedDocument(IssuanceResult result) {
        assertThat(result).isInstanceOf(IssuanceResult.Issued.class);
        return ((IssuanceResult.Issued) result).document();
    }

    private void assertTechnicalFailure(CreditNote creditNote) {
        assertThatThrownBy(() -> provider.issue(creditNote, invoice))
                .isInstanceOf(ElectronicInvoiceProviderUnavailableException.class)
                .hasMessageContaining("nota crédito " + creditNote.id());
    }

    // ----- Emitida -----

    @Test
    @DisplayName("la nota validada → Issued con número, CUDE, enlaces, fecha y total oficial; la petición es la esperada")
    void validatedCreditNoteIsIssued() throws Exception {
        respond(201, fixture("credit-note-validated-partial.json", id));

        ElectronicDocument document = issuedDocument(provider.issue(partial(), invoice));

        assertThat(document.number()).isEqualTo(NUMBER);
        assertThat(document.uniqueCode()).hasSize(96).startsWith("b659dc4457ab");   // el CUDE, no un CUFE
        assertThat(document.qrUrl().toString()).contains("documentkey=b659dc4457ab");
        assertThat(document.publicUrl()).hasToString(
                "https://app-sandbox.factus.com.co/documents/credit-notes/0000test0000partial");
        assertThat(document.validatedAt()).isEqualTo(Instant.parse("2026-10-22T20:17:42Z"));   // 03:17:42 PM en Colombia
        assertThat(document.officialTotal()).isEqualTo(Money.of("39158.33"));
        String sent = FACTUS.findAll(postRequestedFor(urlEqualTo(VALIDATE_PATH))).getFirst().getBodyAsString();
        JSONAssert.assertEquals(fixture("credit-note-request-partial.json", id), sent, JSONCompareMode.STRICT);
    }

    @Test
    @DisplayName("usa el mismo token Bearer y User-Agent que las facturas")
    void sendsAuthenticatedRequest() {
        respond(201, fixture("credit-note-validated-partial.json", id));

        provider.issue(partial(), invoice);

        FACTUS.verify(postRequestedFor(urlEqualTo(VALIDATE_PATH))
                .withHeader("Authorization", equalTo("Bearer access-nc-0123456789abcdef"))
                .withHeader("User-Agent", equalTo("FacturaFlow-Test/1.0"))
                .withHeader("Content-Type", containing("application/json")));
    }

    @Test
    @DisplayName("una anulación envía el código 2 y todas las líneas, incluida la excluida de IVA")
    void annulmentSendsAllLines() {
        CreditNote annulment = annulment(id, invoice);
        respond(201, fixture("credit-note-validated-annulment.json", id));

        ElectronicDocument document = issuedDocument(provider.issue(annulment, invoice));

        assertThat(document.number()).isEqualTo("NC1663");
        assertThat(document.officialTotal()).isEqualTo(Money.of("147475.01")).isEqualTo(annulment.total());
        String sent = FACTUS.findAll(postRequestedFor(urlEqualTo(VALIDATE_PATH))).getFirst().getBodyAsString();
        assertThat(sent).contains("\"correction_concept_code\":\"2\"", "\"bill_number\":\"" + CREDITED_BILL_NUMBER + "\"",
                "\"is_excluded\":true", "\"amount\":147475.01");
    }

    @Test
    @DisplayName("las notificaciones (lista de textos, p. ej. CBF02) no son un rechazo: se registran sus códigos")
    void notificationsAreOnlyLogged(CapturedOutput output) {
        respond(201, fixture("credit-note-validated-partial.json", id));

        issuedDocument(provider.issue(partial(), invoice));

        assertThat(output).contains("con notificaciones: CBF02, RUT01");
        assertThat(output).doesNotContain("0000test0000partial");   // la URL pública nunca va al log
    }

    @Test
    @DisplayName("si coincide la identidad pero no el total, se emite con el total oficial y se registra un WARN")
    void officialTotalMismatchIsIssuedWithWarning(CapturedOutput output) {
        respond(201, fixture("credit-note-validated-partial.json", id)
                .replace("\"total\": \"39158.33\"", "\"total\": \"39158.34\""));

        ElectronicDocument document = issuedDocument(provider.issue(partial(), invoice));

        assertThat(document.officialTotal()).isEqualTo(Money.of("39158.34"));
        assertThat(output).contains("WARN", "nota crédito " + id, "oficial 39158.34", "calculado 39158.33");
    }

    // ----- Otra nota (idempotencia ciega) -----

    @Test
    @DisplayName("otro reference_code es otra nota: falla técnica y ERROR, nunca Issued")
    void otherReferenceCodeIsNotIssued(CapturedOutput output) {
        UUID other = UUID.randomUUID();
        respond(201, fixture("credit-note-validated-partial.json", other));

        assertTechnicalFailure(partial());
        assertThat(output).contains("ERROR", "no corresponde", "reference_code esperado " + id, "recibido " + other);
    }

    @Test
    @DisplayName("otra identificación del cliente: falla técnica y ERROR")
    void otherCustomerIsNotIssued(CapturedOutput output) {
        respond(201, fixture("credit-note-validated-partial.json", id)
                .replace("\"identification\": \"1020304050\"", "\"identification\": \"890903938\""));

        assertTechnicalFailure(partial());
        assertThat(output).contains("ERROR", "identificación del cliente esperada 13 1020304050",
                "recibida 13 890903938");
    }

    @Test
    @DisplayName("si bill.number no es nuestra factura, la nota acredita otra: falla técnica y ERROR")
    void otherCreditedBillIsNotIssued(CapturedOutput output) {
        respond(201, fixture("credit-note-validated-partial.json", id)
                .replace("\"number\": \"" + CREDITED_BILL_NUMBER + "\"", "\"number\": \"SETP990028958\""));

        assertTechnicalFailure(partial());
        assertThat(output).contains("ERROR", "factura acreditada (bill.number) esperada " + CREDITED_BILL_NUMBER,
                "recibida SETP990028958");
    }

    @Test
    @DisplayName("una respuesta sin bill también es una identidad distinta (no se asume nada)")
    void missingBillIsNotIssued() {
        respond(201, fixture("credit-note-validated-partial.json", id).replace("\"bill\": {", "\"bill_x\": {"));

        assertTechnicalFailure(partial());
    }

    // ----- Rechazada y configuración -----

    @Test
    @DisplayName("un 422 por los datos de la nota → Rejected con los mensajes de Factus")
    void dataErrorIsRejected() {
        respond(422, fixture("credit-note-422-item.json"));

        assertThat(provider.issue(partial(), invoice)).isInstanceOfSatisfying(IssuanceResult.Rejected.class,
                rejected -> assertThat(rejected.reason())
                        .isEqualTo("Factus no validó la nota crédito: items.0.price: El campo precio es obligatorio."));
    }

    @Test
    @DisplayName("un 422 por el rango de notas es configuración: falla técnica que dice qué variable revisar")
    void numberingRangeErrorIsTechnical(CapturedOutput output) {
        respond(422, fixture("credit-note-422-numbering-range.json"));

        assertThatThrownBy(() -> provider.issue(partial(), invoice))
                .isInstanceOf(ElectronicInvoiceProviderUnavailableException.class)
                .hasMessageContaining("FACTUS_CREDIT_NOTE_NUMBERING_RANGE_ID");
        assertThat(output).contains("ERROR", "configuración del adaptador",
                "invoicing.factus.credit-note-numbering-range-id", "sigue PENDING");
    }

    @Test
    @DisplayName("un 422 'la factura referenciada no existe' no es culpa de la nota: falla técnica (factura de otra cuenta)")
    void unknownBillIsTechnical(CapturedOutput output) {
        respond(422, fixture("credit-note-422-unknown-bill.json"));

        assertTechnicalFailure(partial());
        assertThat(output).contains("ERROR", "misma cuenta de Factus", "La factura referenciada no existe.");
    }

    @Test
    @DisplayName("is_validated false (NO VERIFICADO) y 409 (NO VERIFICADO) → falla técnica: la nota sigue PENDING")
    void notValidatedAndPendingAreTechnical() {
        respond(201, fixture("credit-note-validated-partial.json", id)
                .replace("\"is_validated\": true", "\"is_validated\": false"));
        assertTechnicalFailure(partial());

        respond(409, fixture("bill-409-pending.json"));
        assertThatThrownBy(() -> provider.issue(partial(), invoice))
                .isInstanceOf(ElectronicInvoiceProviderUnavailableException.class)
                .hasMessageContaining("HTTP 409");
    }

    // ----- PDF -----

    @Test
    @DisplayName("el PDF de la nota se descarga de su propio endpoint y se decodifica")
    void downloadsPdf() {
        CreditNote note = partial();
        note.markAsIssued(issuedDocument(issueWith("credit-note-validated-partial.json")));
        byte[] pdfBytes = "%PDF-1.4\nnota\n%%EOF".getBytes(StandardCharsets.US_ASCII);
        FACTUS.stubFor(get(urlEqualTo("/v2/credit-notes/" + NUMBER + "/download-pdf")).willReturn(json(200,
                fixture("pdf.json").replace("{{PDF_BASE64}}", Base64.getEncoder().encodeToString(pdfBytes))
                        .replace("fv10007890020002600029353", "nc10007890020002600002877"))));

        InvoicePdf pdf = provider.downloadPdf(note);

        assertThat(pdf.content()).isEqualTo(pdfBytes);
        assertThat(pdf.fileName()).isEqualTo("nc10007890020002600002877.pdf");
    }

    @Test
    @DisplayName("un error al descargar el PDF de la nota es una falla técnica de la NOTA")
    void pdfErrorIsTechnical() {
        CreditNote note = partial();
        note.markAsIssued(issuedDocument(issueWith("credit-note-validated-partial.json")));
        FACTUS.stubFor(get(urlEqualTo("/v2/credit-notes/" + NUMBER + "/download-pdf"))
                .willReturn(json(500, fixture("error-500.json"))));

        assertThatThrownBy(() -> provider.downloadPdf(note))
                .isInstanceOf(ElectronicInvoiceProviderUnavailableException.class)
                .hasMessageContaining("nota crédito " + note.id()).hasMessageContaining("HTTP 500");
    }

    private IssuanceResult issueWith(String fixtureName) {
        respond(201, fixture(fixtureName, id));
        return provider.issue(partial(), invoice);
    }
}
