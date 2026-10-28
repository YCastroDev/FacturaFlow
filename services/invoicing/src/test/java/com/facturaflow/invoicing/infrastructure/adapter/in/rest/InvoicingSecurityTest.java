package com.facturaflow.invoicing.infrastructure.adapter.in.rest;

import static com.facturaflow.invoicing.domain.model.CreditNoteFixtures.credit;
import static com.facturaflow.invoicing.domain.model.CreditNoteFixtures.issuedInvoiceToCredit;
import static com.facturaflow.invoicing.domain.model.CreditNoteFixtures.partialReturn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.facturaflow.invoicing.FakeKeycloak;
import com.facturaflow.invoicing.application.port.in.CreateCreditNoteResult;
import com.facturaflow.invoicing.application.port.in.CreateCreditNoteUseCase;
import com.facturaflow.invoicing.application.port.in.CreateInvoiceResult;
import com.facturaflow.invoicing.application.port.in.CreateInvoiceUseCase;
import com.facturaflow.invoicing.application.port.in.GetCreditBalanceUseCase;
import com.facturaflow.invoicing.application.port.in.GetCreditNotePdfUseCase;
import com.facturaflow.invoicing.application.port.in.GetCreditNoteUseCase;
import com.facturaflow.invoicing.application.port.in.GetInvoicePdfUseCase;
import com.facturaflow.invoicing.application.port.in.GetInvoiceUseCase;
import com.facturaflow.invoicing.application.port.in.IssueCreditNoteUseCase;
import com.facturaflow.invoicing.application.port.in.IssueInvoiceUseCase;
import com.facturaflow.invoicing.application.port.out.InvoicePdf;
import com.facturaflow.invoicing.domain.model.CreditBalance;
import com.facturaflow.invoicing.domain.model.CreditNote;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.infrastructure.config.SecurityConfiguration;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Matriz de permisos de invoicing (ADR 0018), fila por fila: sin token 401, con un rol que no está en la fila 403 y
 * con uno que sí, la respuesta del controlador (2xx). Los tokens tienen la forma de los del realm y los valida el
 * decodificador REAL de Boot contra las claves de {@link FakeKeycloak}.
 */
@WebMvcTest({InvoiceController.class, CreditNoteController.class})
@Import(SecurityConfiguration.class)
class InvoicingSecurityTest {

    private static final String INVOICE_BODY = """
            { "referenceCode": "SALE-0001",
              "customer": { "documentType": "NIT", "documentNumber": "890903938", "checkDigit": 8,
                            "personType": "LEGAL_ENTITY", "companyName": "Cliente Ejemplo S.A.S.",
                            "taxScheme": "IVA", "fiscalResponsibilities": [ "LARGE_TAXPAYER" ],
                            "municipalityCode": "11001" },
              "lines": [ { "code": "SKU-1", "name": "Producto", "quantity": 1, "unitPrice": 1000, "unit": "UNIT",
                           "taxes": [] } ],
              "paymentMethod": "CASH", "paymentTerms": { "type": "CASH" } }""";
    private static final String ID = "7f1c2a4e-0000-4000-8000-000000000001";

    /** Identidades posibles: los tres roles del realm y el cliente de máquina de sales (solo catalog.read). */
    private static final List<String> IDENTITIES = List.of("admin", "vendedor", "contador", "sales-service");
    private static final Set<String> ACCOUNTING = Set.of("admin", "contador");

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", FakeKeycloak::jwkSetUri);
    }

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private CreateInvoiceUseCase createInvoice;

    @MockitoBean
    private GetInvoiceUseCase getInvoice;

    @MockitoBean
    private IssueInvoiceUseCase issueInvoice;

    @MockitoBean
    private GetInvoicePdfUseCase getInvoicePdf;

    @MockitoBean
    private GetCreditBalanceUseCase getCreditBalance;

    @MockitoBean
    private CreateCreditNoteUseCase createCreditNote;

    @MockitoBean
    private GetCreditNoteUseCase getCreditNote;

    @MockitoBean
    private IssueCreditNoteUseCase issueCreditNote;

    @MockitoBean
    private GetCreditNotePdfUseCase getCreditNotePdf;

    @BeforeEach
    void stubs() {
        Invoice invoice = issuedInvoiceToCredit();
        CreditBalance balance = CreditBalance.of(invoice, List.of());
        CreditNote note = partialReturn(balance, "DEV-1", credit(1, "1"));
        InvoicePdf pdf = new InvoicePdf("documento", new byte[] {'%', 'P', 'D', 'F'});
        given(createInvoice.create(any())).willReturn(CreateInvoiceResult.created(invoice));
        given(getInvoice.byId(any())).willReturn(invoice);
        given(getInvoice.byReferenceCode(any())).willReturn(invoice);
        given(issueInvoice.issue(any())).willReturn(invoice);
        given(getInvoicePdf.pdf(any())).willReturn(pdf);
        given(getCreditBalance.forInvoice(any())).willReturn(balance);
        given(createCreditNote.create(any())).willReturn(CreateCreditNoteResult.created(note));
        given(getCreditNote.byId(any())).willReturn(note);
        given(getCreditNote.byInvoice(any())).willReturn(List.of(note));
        given(issueCreditNote.issue(any())).willReturn(note);
        given(getCreditNotePdf.pdf(any())).willReturn(pdf);
    }

    record Row(HttpMethod method, String uri, String body, Set<String> allowed) {

        @Override
        public String toString() {
            return method + " " + uri + " → " + allowed;
        }
    }

    static Stream<Row> matrix() {
        return Stream.of(
                new Row(HttpMethod.POST, "/api/v1/invoices", INVOICE_BODY, ACCOUNTING),
                new Row(HttpMethod.GET, "/api/v1/invoices?referenceCode=SALE-0001", null, ACCOUNTING),
                new Row(HttpMethod.GET, "/api/v1/invoices/" + ID, null, ACCOUNTING),
                new Row(HttpMethod.POST, "/api/v1/invoices/" + ID + "/issue", null, ACCOUNTING),
                new Row(HttpMethod.GET, "/api/v1/invoices/" + ID + "/pdf", null, ACCOUNTING),
                new Row(HttpMethod.POST, "/api/v1/invoices/" + ID + "/credit-notes", ApiExamples.PARTIAL_RETURN,
                        ACCOUNTING),
                new Row(HttpMethod.GET, "/api/v1/invoices/" + ID + "/credit-notes", null, ACCOUNTING),
                new Row(HttpMethod.GET, "/api/v1/credit-notes/" + ID, null, ACCOUNTING),
                new Row(HttpMethod.POST, "/api/v1/credit-notes/" + ID + "/issue", null, ACCOUNTING),
                new Row(HttpMethod.GET, "/api/v1/credit-notes/" + ID + "/pdf", null, ACCOUNTING));
    }

    static Stream<Arguments> matrixByIdentity() {
        return matrix().flatMap(row -> IDENTITIES.stream().map(identity -> Arguments.of(row, identity)));
    }

    private MvcTestResult call(Row row, String authorization) {
        MockMvcRequestBuilder request = mvc.method(row.method()).uri(row.uri());
        if (row.body() != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(row.body());
        }
        if (authorization != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return request.exchange();
    }

    private static String tokenOf(String identity) {
        return "Bearer " + (identity.equals("sales-service")
                ? FakeKeycloak.serviceToken("catalog.read")
                : FakeKeycloak.userToken(identity));
    }

    private static void assertProblem(MvcTestResult result, HttpStatus status, String slug) {
        assertThat(result).hasStatus(status).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo("urn:facturaflow:problem:" + slug);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("matrix")
    @DisplayName("sin token: 401 en Problem Details con WWW-Authenticate: Bearer")
    void withoutTokenIs401(Row row) {
        MvcTestResult result = call(row, null);

        assertProblem(result, HttpStatus.UNAUTHORIZED, "unauthorized");
        assertThat(result).hasHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    }

    @ParameterizedTest(name = "{0} con {1}")
    @MethodSource("matrixByIdentity")
    @DisplayName("con token: el rol de la fila pasa (2xx); cualquier otro, 403 en Problem Details")
    void roleDecides(Row row, String identity) {
        MvcTestResult result = call(row, tokenOf(identity));

        if (row.allowed().contains(identity)) {
            assertThat(result.getResponse().getStatus()).as(row + " con " + identity).isBetween(200, 299);
        } else {
            assertProblem(result, HttpStatus.FORBIDDEN, "forbidden");
        }
    }

    static Stream<Arguments> invalidTokens() {
        return Stream.of(
                Arguments.of("de otro emisor", FakeKeycloak.userToken(
                        c -> c.issuer("http://localhost:8180/realms/otro"), "admin")),
                Arguments.of("sin la audiencia facturaflow-api", FakeKeycloak.userToken(
                        c -> c.audience("account"), "admin")),
                Arguments.of("firmado con otra clave", FakeKeycloak.tokenSignedWithUnknownKey("admin")));
    }

    @ParameterizedTest(name = "token {0}")
    @MethodSource("invalidTokens")
    @DisplayName("un token inválido es 401 invalid_token, sin decir por qué")
    void invalidTokenIs401(String reason, String token) {
        MvcTestResult result = mvc.get().uri("/api/v1/invoices/" + ID)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();

        assertProblem(result, HttpStatus.UNAUTHORIZED, "unauthorized");
        assertThat(result).hasHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");
    }
}
