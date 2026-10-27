package com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus;

import static com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusTestData.CLIENT_SECRET;
import static com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusTestData.GENEROUS_READ_TIMEOUT;
import static com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusTestData.PASSWORD;
import static com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusTestData.SHORT_READ_TIMEOUT;
import static com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusTestData.SLOW_RESPONSE;
import static com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusTestData.cashInvoice;
import static com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusTestData.creditInvoice;
import static com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusTestData.fixture;
import static com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusTestData.properties;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.invoicing.application.exception.ElectronicInvoiceProviderUnavailableException;
import com.facturaflow.invoicing.application.port.out.InvoicePdf;
import com.facturaflow.invoicing.application.port.out.IssuanceResult;
import com.facturaflow.invoicing.domain.model.ElectronicDocument;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.domain.model.Money;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * El adaptador completo (RestClient, TokenProvider, traductor e intérprete) contra un Factus falso
 * (WireMock) que responde con los cuerpos reales del spike 0001. Los casos que el spike no observó
 * usan fixtures marcados como NO VERIFICADOS (ver {@code src/test/resources/factus/README.md}).
 *
 * <p>Usa UN solo intento ({@link FactusTestData#singleAttempt()}): aquí se prueba qué significa cada
 * respuesta. Los reintentos, el circuit breaker y el limitador están en {@code FactusResilienceTest}.
 */
@ExtendWith(OutputCaptureExtension.class)
class FactusElectronicInvoiceProviderTest {

    private static final String TOKEN_PATH = FactusTokenEndpoint.PATH;
    private static final String VALIDATE_PATH = FactusClient.VALIDATE_PATH;
    private static final String NUMBER = "SETP990028958";
    private static final String PDF_PATH = "/v2/bills/" + NUMBER + "/download-pdf";
    private static final String ACCESS_1 = "access-token-uno-0123456789abcdef";
    private static final String ACCESS_2 = "access-token-dos-0123456789abcdef";
    private static final String REFRESH_1 = "refresh-token-uno-0123456789abcdef";

    @RegisterExtension
    static final WireMockExtension FACTUS = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private final UUID id = UUID.randomUUID();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-22T15:00:00Z"));
    private FactusElectronicInvoiceProvider provider;

    @BeforeEach
    void setUp() {
        provider = FactusElectronicInvoiceProvider.create(properties(FACTUS.baseUrl()), clock);
        stubPasswordGrant(ACCESS_1, REFRESH_1);
    }

    // ----- Stubs -----

    private static String tokenBody(String access, String refresh) {
        return "{\"token_type\":\"Bearer\",\"expires_in\":3600,\"access_token\":\"" + access
                + "\",\"refresh_token\":\"" + refresh + "\"}";
    }

    private static void stubPasswordGrant(String access, String refresh) {
        FACTUS.stubFor(post(urlEqualTo(TOKEN_PATH)).withRequestBody(containing("grant_type=password"))
                .willReturn(json(200, tokenBody(access, refresh))));
    }

    private static ResponseDefinitionBuilder json(int status, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
    }

    private static MappingBuilder validate() {
        return post(urlEqualTo(VALIDATE_PATH));
    }

    private void respondToValidation(int status, String fixture) {
        FACTUS.stubFor(validate().willReturn(json(status, fixture(fixture, id))));
    }

    private Invoice cash() {
        return cashInvoice(id);
    }

    private ElectronicDocument issuedDocument(IssuanceResult result) {
        assertThat(result).isInstanceOf(IssuanceResult.Issued.class);
        return ((IssuanceResult.Issued) result).document();
    }

    private void assertTechnicalFailure(Invoice invoice) {
        assertThatThrownBy(() -> provider.issue(invoice))
                .isInstanceOf(ElectronicInvoiceProviderUnavailableException.class)
                .hasMessageContaining(invoice.id().toString());
    }

    private static int validations() {
        return FACTUS.findAll(postRequestedFor(urlEqualTo(VALIDATE_PATH))).size();
    }

    // ----- Emitida -----

    @Test
    @DisplayName("is_validated true → Issued con número, CUFE, enlaces, fecha de validación y total oficial")
    void validatedInvoiceIsIssued() throws Exception {
        respondToValidation(201, "bill-validated-cash.json");

        ElectronicDocument document = issuedDocument(provider.issue(cash()));

        assertThat(document.number()).isEqualTo(NUMBER);
        assertThat(document.uniqueCode()).hasSize(96).startsWith("308970cce2ea");
        assertThat(document.qrUrl().toString()).startsWith("https://catalogo-vpfe-hab.dian.gov.co/document/searchqr");
        assertThat(document.publicUrl()).hasToString("https://app-sandbox.factus.com.co/documents/bills/0000test0000cash");
        // "21-10-2026 01:35:11 PM" es hora de Colombia (UTC-5)
        assertThat(document.validatedAt()).isEqualTo(Instant.parse("2026-10-21T18:35:11Z"));
        assertThat(document.officialTotal()).isEqualTo(Money.of("182070.00"));
        String sent = FACTUS.findAll(postRequestedFor(urlEqualTo(VALIDATE_PATH))).getFirst().getBodyAsString();
        JSONAssert.assertEquals(fixture("request-cash.json", id), sent, JSONCompareMode.STRICT);
    }

    @Test
    @DisplayName("la petición lleva el token Bearer, el User-Agent propio y JSON")
    void sendsAuthenticatedRequest() {
        respondToValidation(201, "bill-validated-cash.json");

        provider.issue(cash());

        FACTUS.verify(postRequestedFor(urlEqualTo(VALIDATE_PATH))
                .withHeader("Authorization", equalTo("Bearer " + ACCESS_1))
                .withHeader("User-Agent", equalTo("FacturaFlow-Test/1.0"))
                .withHeader("Content-Type", containing("application/json")));
        FACTUS.verify(postRequestedFor(urlEqualTo(TOKEN_PATH))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withRequestBody(containing("client_secret=" + CLIENT_SECRET)));
    }

    @Test
    @DisplayName("las notificaciones de la DIAN en data.errors no impiden la emisión: se registran sus códigos")
    void notificationsAreOnlyLogged(CapturedOutput output) {
        respondToValidation(201, "bill-validated-cash.json");

        issuedDocument(provider.issue(cash()));

        assertThat(output).contains("con notificaciones", "FAJ43b", "RUT01");
    }

    @Test
    @DisplayName("la factura a crédito con redondeo coincide con el total de Factus y se emite sin aviso")
    void creditInvoiceWithRounding(CapturedOutput output) {
        respondToValidation(201, "bill-validated-credit.json");

        ElectronicDocument document = issuedDocument(provider.issue(creditInvoice(id)));

        assertThat(document.officialTotal()).isEqualTo(Money.of("147475.01"));
        assertThat(output).doesNotContain("difiere del calculado");
    }

    @Test
    @DisplayName("si coincide la identidad pero no el total, se emite con el total oficial y se registra un WARN")
    void officialTotalMismatchIsIssuedWithWarning(CapturedOutput output) {
        FACTUS.stubFor(validate().willReturn(json(201, fixture("bill-validated-cash.json", id)
                .replace("\"total\": \"182070.00\"", "\"total\": \"182070.01\""))));

        ElectronicDocument document = issuedDocument(provider.issue(cash()));

        assertThat(document.officialTotal()).isEqualTo(Money.of("182070.01"));
        assertThat(output).contains("WARN", "difiere del calculado", "oficial 182070.01", "calculado 182070.00");
    }

    // ----- Otra factura (idempotencia ciega) -----

    @Test
    @DisplayName("si el reference_code de la respuesta no es el nuestro, es otra factura: falla técnica y ERROR")
    void otherReferenceCodeIsNotIssued(CapturedOutput output) {
        UUID other = UUID.randomUUID();
        FACTUS.stubFor(validate().willReturn(json(201, fixture("bill-validated-cash.json", other))));

        assertTechnicalFailure(cash());
        assertThat(output).contains("ERROR", "no corresponde", "reference_code esperado " + id, "recibido " + other);
    }

    @Test
    @DisplayName("si la identificación del cliente no es la nuestra, es otra factura: falla técnica y ERROR")
    void otherCustomerIsNotIssued(CapturedOutput output) {
        FACTUS.stubFor(validate().willReturn(json(201, fixture("bill-validated-cash.json", id)
                .replace("\"identification\": \"890903938\"", "\"identification\": \"900123456\""))));

        assertTechnicalFailure(cash());
        assertThat(output).contains("ERROR", "identificación del cliente esperada 31 890903938",
                "recibida 31 900123456");
    }

    // ----- Rechazada -----

    @Test
    @DisplayName("un 422 por los datos de la factura → Rejected con los mensajes de Factus como motivo")
    void validationErrorIsRejected() {
        respondToValidation(422, "bill-422-wrong-dv.json");

        IssuanceResult result = provider.issue(cash());

        assertThat(result).isInstanceOfSatisfying(IssuanceResult.Rejected.class, rejected ->
                assertThat(rejected.reason()).isEqualTo(
                        "Factus no validó la factura: customer.dv: El campo dígito de verificación no es valido"));
    }

    @Test
    @DisplayName("un 422 de fecha de vencimiento también es un rechazo de negocio")
    void pastDueDateIsRejected() {
        respondToValidation(422, "bill-422-past-due-date.json");

        assertThat(provider.issue(creditInvoice(id))).isInstanceOfSatisfying(IssuanceResult.Rejected.class,
                rejected -> assertThat(rejected.reason()).contains("payment_details.0.due_date", "posterior o igual"));
    }

    @Test
    @DisplayName("un 422 por la configuración (numbering_range_id) no es culpa de la factura: falla técnica")
    void configurationErrorIsTechnical(CapturedOutput output) {
        respondToValidation(422, "bill-422-numbering-range.json");

        assertThatThrownBy(() -> provider.issue(cash()))
                .isInstanceOf(ElectronicInvoiceProviderUnavailableException.class)
                .hasMessageContaining("FACTUS_NUMBERING_RANGE_ID");
        assertThat(output).contains("ERROR", "configuración del adaptador",
                "Revise invoicing.factus.numbering-range-id (variable FACTUS_NUMBERING_RANGE_ID)",
                "El campo id rango de numeración es inválido.", "sigue PENDING");
    }

    // ----- Fallas técnicas -----

    @Test
    @DisplayName("is_validated false (NO VERIFICADO en el spike) → falla técnica: la factura sigue PENDING")
    void notValidatedIsTechnical() {
        respondToValidation(201, "bill-not-validated.json");

        assertTechnicalFailure(cash());
    }

    @Test
    @DisplayName("409 pendiente por enviar a la DIAN (NO VERIFICADO) → falla técnica")
    void pendingConflictIsTechnical() {
        respondToValidation(409, "bill-409-pending.json");

        assertThatThrownBy(() -> provider.issue(cash()))
                .isInstanceOf(ElectronicInvoiceProviderUnavailableException.class)
                .hasMessageContaining("HTTP 409").hasMessageContaining("pendiente");
    }

    @Test
    @DisplayName("429 (NO VERIFICADO) → falla técnica con el Retry-After en el mensaje (con un solo intento)")
    void rateLimitIsTechnical() {
        FACTUS.stubFor(validate().willReturn(json(429, fixture("error-429.json")).withHeader("Retry-After", "17")));

        assertThatThrownBy(() -> provider.issue(cash()))
                .isInstanceOf(ElectronicInvoiceProviderUnavailableException.class)
                .hasMessageContaining("HTTP 429").hasMessageContaining("Retry-After: 17");
        assertThat(validations()).isEqualTo(1);
    }

    @Test
    @DisplayName("500 (NO VERIFICADO) → falla técnica (con un solo intento)")
    void serverErrorIsTechnical() {
        respondToValidation(500, "error-500.json");

        assertTechnicalFailure(cash());
        assertThat(validations()).isEqualTo(1);
    }

    @Test
    @DisplayName("un timeout de lectura → falla técnica (nunca un Rejected)")
    void readTimeoutIsTechnical() {
        provider = FactusElectronicInvoiceProvider.create(
                properties(FACTUS.baseUrl(), false, null, SHORT_READ_TIMEOUT), clock);
        FACTUS.stubFor(validate().willReturn(json(201, fixture("bill-validated-cash.json", id))
                .withFixedDelay((int) SLOW_RESPONSE.toMillis())));

        assertThatThrownBy(() -> provider.issue(cash()))
                .isInstanceOf(ElectronicInvoiceProviderUnavailableException.class)
                .hasMessageContaining("sin respuesta de Factus");
    }

    @Test
    @DisplayName("una conexión cortada → falla técnica")
    void networkErrorIsTechnical() {
        FACTUS.stubFor(validate().willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertTechnicalFailure(cash());
    }

    @Test
    @DisplayName("un cuerpo ilegible en un 201 → falla técnica")
    void unreadableBodyIsTechnical() {
        FACTUS.stubFor(validate().willReturn(aResponse().withStatus(201).withBody("<html>Cloudflare</html>")));

        assertTechnicalFailure(cash());
    }

    @Test
    @DisplayName("si Factus no autentica (credenciales rechazadas), es una falla técnica y no se valida nada")
    void authenticationFailureIsTechnical() {
        FACTUS.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(json(401, fixture("error-401.json"))));

        assertTechnicalFailure(cash());
        assertThat(validations()).isZero();
    }

    // ----- Token -----

    @Nested
    @DisplayName("Token")
    class Token {

        @Test
        @DisplayName("ante un 401, invalida el token, lo renueva con el refresh token y reintenta UNA vez")
        void renewsAndRetriesOnceOn401() {
            FACTUS.stubFor(post(urlEqualTo(TOKEN_PATH)).withRequestBody(containing("grant_type=refresh_token"))
                    .withRequestBody(containing("refresh_token=" + REFRESH_1))
                    .willReturn(json(200, tokenBody(ACCESS_2, "refresh-token-dos-0123456789"))));
            FACTUS.stubFor(validate().withHeader("Authorization", equalTo("Bearer " + ACCESS_1))
                    .willReturn(json(401, fixture("error-401.json"))));
            FACTUS.stubFor(validate().withHeader("Authorization", equalTo("Bearer " + ACCESS_2))
                    .willReturn(json(201, fixture("bill-validated-cash.json", id))));

            issuedDocument(provider.issue(cash()));

            assertThat(validations()).isEqualTo(2);
        }

        @Test
        @DisplayName("si después de renovar recibe otro 401, no vuelve a intentar: falla técnica")
        void doesNotRetryTwice() {
            FACTUS.stubFor(post(urlEqualTo(TOKEN_PATH)).withRequestBody(containing("grant_type=refresh_token"))
                    .willReturn(json(200, tokenBody(ACCESS_2, "refresh-token-dos-0123456789"))));
            FACTUS.stubFor(validate().willReturn(json(401, fixture("error-401.json"))));

            assertThatThrownBy(() -> provider.issue(cash()))
                    .isInstanceOf(ElectronicInvoiceProviderUnavailableException.class)
                    .hasMessageContaining("HTTP 401");
            assertThat(validations()).isEqualTo(2);
        }

        @Test
        @DisplayName("si el refresh token fue revocado, vuelve a autenticarse con usuario y contraseña")
        void reauthenticatesWhenRefreshIsRevoked() {
            FACTUS.stubFor(post(urlEqualTo(TOKEN_PATH)).withRequestBody(containing("grant_type=refresh_token"))
                    .willReturn(json(401, fixture("token-refresh-revoked.json"))));
            FACTUS.stubFor(post(urlEqualTo(TOKEN_PATH)).withRequestBody(containing("grant_type=password"))
                    .inScenario("auth").whenScenarioStateIs(Scenario.STARTED).willSetStateTo("second")
                    .willReturn(json(200, tokenBody(ACCESS_1, REFRESH_1))));
            FACTUS.stubFor(post(urlEqualTo(TOKEN_PATH)).withRequestBody(containing("grant_type=password"))
                    .inScenario("auth").whenScenarioStateIs("second")
                    .willReturn(json(200, tokenBody(ACCESS_2, "refresh-token-dos-0123456789"))));
            FACTUS.stubFor(validate().withHeader("Authorization", equalTo("Bearer " + ACCESS_1))
                    .willReturn(json(401, fixture("error-401.json"))));
            FACTUS.stubFor(validate().withHeader("Authorization", equalTo("Bearer " + ACCESS_2))
                    .willReturn(json(201, fixture("bill-validated-cash.json", id))));

            issuedDocument(provider.issue(cash()));

            assertThat(FACTUS.findAll(postRequestedFor(urlEqualTo(TOKEN_PATH))
                    .withRequestBody(containing("grant_type=password")))).hasSize(2);
        }

        @Test
        @DisplayName("con el token vencido según expires_in, lo renueva ANTES de llamar (sin esperar un 401)")
        void renewsExpiredTokenBeforeCalling() {
            FACTUS.stubFor(post(urlEqualTo(TOKEN_PATH)).withRequestBody(containing("grant_type=refresh_token"))
                    .willReturn(json(200, tokenBody(ACCESS_2, "refresh-token-dos-0123456789"))));
            respondToValidation(201, "bill-validated-cash.json");
            provider.issue(cash());

            clock.advance(Duration.ofSeconds(3600));
            provider.issue(cash());

            FACTUS.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH))
                    .withRequestBody(containing("grant_type=refresh_token")));
            FACTUS.verify(1, postRequestedFor(urlEqualTo(VALIDATE_PATH))
                    .withHeader("Authorization", equalTo("Bearer " + ACCESS_2)));
            assertThat(validations()).isEqualTo(2);
        }

        @Test
        @DisplayName("ni los tokens, ni el client_secret, ni la contraseña, ni la URL pública llegan a los logs")
        void secretsNeverReachTheLogs(CapturedOutput output) {
            FACTUS.stubFor(post(urlEqualTo(TOKEN_PATH)).withRequestBody(containing("grant_type=refresh_token"))
                    .willReturn(json(401, fixture("token-refresh-revoked.json"))));
            FACTUS.stubFor(validate().willReturn(json(401, fixture("error-401.json"))));
            assertThatThrownBy(() -> provider.issue(cash()));
            FACTUS.resetAll();
            stubPasswordGrant(ACCESS_2, REFRESH_1);
            respondToValidation(201, "bill-validated-cash.json");
            provider = FactusElectronicInvoiceProvider.create(properties(FACTUS.baseUrl()), clock);
            provider.issue(cash());

            assertThat(output).doesNotContain(ACCESS_1, ACCESS_2, REFRESH_1, CLIENT_SECRET, PASSWORD,
                    "0000test0000cash");
        }
    }

    // ----- PDF -----

    @Nested
    @DisplayName("PDF")
    class Pdf {

        private Invoice issued() {
            respondToValidation(201, "bill-validated-cash.json");
            Invoice invoice = cash();
            invoice.markAsIssued(issuedDocument(provider.issue(invoice)));
            return invoice;
        }

        @Test
        @DisplayName("descarga el PDF por el número de la factura y decodifica el Base64")
        void downloadsPdf() {
            byte[] content = "%PDF-1.4\n%contenido de prueba\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
            FACTUS.stubFor(get(urlEqualTo(PDF_PATH)).willReturn(json(200, fixture("pdf.json")
                    .replace("{{PDF_BASE64}}", Base64.getEncoder().encodeToString(content)))));

            InvoicePdf pdf = provider.downloadPdf(issued());

            assertThat(pdf.fileName()).isEqualTo("fv10007890020002600029353.pdf");
            assertThat(pdf.content()).isEqualTo(content);
            FACTUS.verify(getRequestedFor(urlEqualTo(PDF_PATH)).withHeader("Authorization", equalTo("Bearer " + ACCESS_1)));
        }

        @Test
        @DisplayName("si lo que llega no es un PDF, es una falla técnica")
        void rejectsContentThatIsNotPdf() {
            FACTUS.stubFor(get(urlEqualTo(PDF_PATH)).willReturn(json(200, fixture("pdf.json")
                    .replace("{{PDF_BASE64}}", Base64.getEncoder().encodeToString("<html>".getBytes(StandardCharsets.US_ASCII))))));
            Invoice issued = issued();

            assertThatThrownBy(() -> provider.downloadPdf(issued))
                    .isInstanceOf(ElectronicInvoiceProviderUnavailableException.class)
                    .hasMessageContaining("no es un PDF");
        }

        @Test
        @DisplayName("si Factus falla al entregar el PDF, es una falla técnica")
        void serverErrorOnPdfIsTechnical() {
            FACTUS.stubFor(get(urlEqualTo(PDF_PATH)).willReturn(json(500, fixture("error-500.json"))));
            Invoice issued = issued();

            assertThatThrownBy(() -> provider.downloadPdf(issued))
                    .isInstanceOf(ElectronicInvoiceProviderUnavailableException.class)
                    .hasMessageContaining("HTTP 500");
        }
    }

    // ----- Configuración -----

    @Test
    @DisplayName("send_email y numbering_range_id configurados llegan a Factus")
    void sendsConfiguredOptions() {
        provider = FactusElectronicInvoiceProvider.create(properties(FACTUS.baseUrl(), true, 389,
                GENEROUS_READ_TIMEOUT), clock);
        respondToValidation(201, "bill-validated-cash.json");

        provider.issue(cash());

        FACTUS.verify(postRequestedFor(urlEqualTo(VALIDATE_PATH))
                .withRequestBody(containing("\"send_email\":true"))
                .withRequestBody(containing("\"numbering_range_id\":389")));
    }
}
