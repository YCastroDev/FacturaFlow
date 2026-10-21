package com.facturaflow.invoicing.infrastructure.adapter.in.rest;

import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.ISSUE_DATE;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.customer;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.electronicDocument;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.excludedLine;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.line;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.pendingInvoice;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.facturaflow.invoicing.application.exception.ElectronicInvoiceProviderUnavailableException;
import com.facturaflow.invoicing.application.exception.InvoiceAlreadyRejectedException;
import com.facturaflow.invoicing.application.exception.InvoiceConcurrentModificationException;
import com.facturaflow.invoicing.application.exception.InvoiceNotFoundException;
import com.facturaflow.invoicing.application.exception.ReferenceCodeConflictException;
import com.facturaflow.invoicing.application.port.in.CreateInvoiceCommand;
import com.facturaflow.invoicing.application.port.in.CreateInvoiceResult;
import com.facturaflow.invoicing.application.port.in.CreateInvoiceUseCase;
import com.facturaflow.invoicing.application.port.in.GetInvoiceUseCase;
import com.facturaflow.invoicing.application.port.in.IssueInvoiceCommand;
import com.facturaflow.invoicing.application.port.in.IssueInvoiceUseCase;
import com.facturaflow.invoicing.domain.exception.InvalidIdentificationException;
import com.facturaflow.invoicing.domain.exception.InvalidInvoiceStateTransitionException;
import com.facturaflow.invoicing.domain.model.DocumentType;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.domain.model.InvoiceId;
import com.facturaflow.invoicing.domain.model.LineTax;
import com.facturaflow.invoicing.domain.model.PaymentMethod;
import com.facturaflow.invoicing.domain.model.PaymentTerms;
import com.facturaflow.invoicing.domain.model.ReferenceCode;
import com.facturaflow.invoicing.domain.model.TaxType;
import com.facturaflow.invoicing.domain.model.UnitOfMeasure;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Capa web aislada: controlador + manejador de errores + Jackson + Bean Validation, con los
 * casos de uso simulados. Verifica códigos HTTP, forma del JSON y Problem Details.
 */
@WebMvcTest(InvoiceController.class)
class InvoiceControllerTest {

    private static final String URL = "/api/v1/invoices";

    private static final String VALID_REQUEST = """
            {
              "referenceCode": "SALE-0001",
              "customer": { "documentType": "NIT", "documentNumber": "890903938", "checkDigit": 8,
                            "companyName": "Cliente Ejemplo S.A.S.", "email": "facturacion@cliente.example" },
              "lines": [
                { "code": "SKU-1", "name": "Producto", "quantity": 2, "unitPrice": 50000.00, "discountRate": 10,
                  "unit": "UNIT", "taxes": [ { "type": "IVA", "rate": 19 }, { "type": "INC", "rate": 8 } ] },
                { "code": "SKU-EXC", "name": "Excluido", "quantity": 1, "unitPrice": 20000, "unit": "BOX",
                  "ivaExcluded": true, "taxes": [] }
              ],
              "paymentMethod": "BANK_TRANSFER",
              "paymentTerms": { "type": "CREDIT", "dueDate": "2026-11-19" },
              "observation": "Entregar en portería"
            }
            """;

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private CreateInvoiceUseCase createInvoice;

    @MockitoBean
    private GetInvoiceUseCase getInvoice;

    @MockitoBean
    private IssueInvoiceUseCase issueInvoice;

    /** Factura de dos líneas: (2 x 50.000 - 10 %) con IVA 19 % + INC 8 %, y una excluida de 20.000. */
    private static Invoice invoice() {
        return Invoice.create(new InvoiceId(UUID.fromString("7f1c2a4e-0000-4000-8000-000000000001")),
                new ReferenceCode("SALE-0001"), customer(),
                List.of(line("2", "50000.00", "10", LineTax.of(TaxType.IVA, "19"), LineTax.of(TaxType.INC, "8")),
                        excludedLine("1", "20000.00")),
                PaymentMethod.BANK_TRANSFER, PaymentTerms.credit(ISSUE_DATE.plusDays(30)), ISSUE_DATE,
                "Entregar en portería");
    }

    private MvcTestResult post(String body) {
        return mvc.post().uri(URL).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private static void assertProblem(MvcTestResult result, HttpStatus status, String slug) {
        assertThat(result).hasStatus(status).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo("urn:facturaflow:problem:" + slug);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(status.value());
        assertThat(result).bodyText().doesNotContain("trace", "exception", "Exception", "hibernate", "jackson");
    }

    // ----- Crear -----

    @Test
    @DisplayName("crear una factura responde 201 con Location y los totales calculados por el dominio")
    void createReturns201WithLocationAndTotals() {
        given(createInvoice.create(any())).willReturn(CreateInvoiceResult.created(invoice()));

        MvcTestResult result = post(VALID_REQUEST);

        assertThat(result).hasStatus(HttpStatus.CREATED)
                .hasHeader("Location", "http://localhost/api/v1/invoices/7f1c2a4e-0000-4000-8000-000000000001");
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("PENDING");
        assertThat(result).bodyJson().extractingPath("$.issueDate").isEqualTo("2026-10-20");
        assertThat(result).bodyJson().extractingPath("$.paymentTerms.type").isEqualTo("CREDIT");
        assertThat(result).bodyJson().extractingPath("$.lines[0].lineNumber").isEqualTo(1);
        assertThat(result).bodyJson().extractingPath("$.lines[0].taxableAmount").isEqualTo(90000.0);
        assertThat(result).bodyJson().extractingPath("$.lines[0].taxes[1].amount").isEqualTo(7200.0);
        assertThat(result).bodyJson().extractingPath("$.totals.subtotal").isEqualTo(120000.0);
        assertThat(result).bodyJson().extractingPath("$.totals.totalDiscount").isEqualTo(10000.0);
        assertThat(result).bodyJson().extractingPath("$.totals.taxes[0].type").isEqualTo("IVA");
        assertThat(result).bodyJson().extractingPath("$.totals.taxes[0].amount").isEqualTo(17100.0);
        assertThat(result).bodyJson().extractingPath("$.totals.taxes[1].type").isEqualTo("INC");
        assertThat(result).bodyJson().extractingPath("$.totals.taxes[1].amount").isEqualTo(7200.0);
        assertThat(result).bodyJson().extractingPath("$.totals.total").isEqualTo(134300.0);
        assertThat(result).bodyJson().doesNotHavePath("$.electronicDocument");
        assertThat(result).bodyJson().doesNotHavePath("$.rejectionReason");
    }

    @Test
    @DisplayName("los montos se envían con dos decimales exactos, sin pasar por números de punto flotante")
    void amountsKeepTwoDecimals() {
        given(createInvoice.create(any())).willReturn(CreateInvoiceResult.created(invoice()));

        assertThat(post(VALID_REQUEST)).bodyText().contains("\"total\":134300.00", "\"unitPrice\":50000.00");
    }

    @Test
    @DisplayName("la petición se traduce al comando con los enums del dominio y los valores por defecto")
    void mapsRequestToCommand() {
        given(createInvoice.create(any())).willReturn(CreateInvoiceResult.created(invoice()));

        post(VALID_REQUEST);

        ArgumentCaptor<CreateInvoiceCommand> captor = ArgumentCaptor.forClass(CreateInvoiceCommand.class);
        verify(createInvoice).create(captor.capture());
        CreateInvoiceCommand command = captor.getValue();
        assertThat(command.referenceCode()).isEqualTo("SALE-0001");
        assertThat(command.customer().documentType()).isEqualTo(DocumentType.NIT);
        assertThat(command.customer().checkDigit()).isEqualTo(8);
        assertThat(command.paymentMethod()).isEqualTo(PaymentMethod.BANK_TRANSFER);
        assertThat(command.paymentTerms()).isEqualTo(new CreateInvoiceCommand.CreditTerms(LocalDate.of(2026, 11, 19)));
        assertThat(command.lines().get(0).ivaExcluded()).isFalse();
        assertThat(command.lines().get(0).taxes()).extracting(CreateInvoiceCommand.Tax::type)
                .containsExactly(TaxType.IVA, TaxType.INC);
        assertThat(command.lines().get(1).discountRate()).isNull();
        assertThat(command.lines().get(1).unit()).isEqualTo(UnitOfMeasure.BOX);
        assertThat(command.lines().get(1).ivaExcluded()).isTrue();
        assertThat(command.lines().get(1).quantity()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    @DisplayName("un reintento idéntico responde 200 con la factura existente y sin Location")
    void identicalRetryReturns200() {
        given(createInvoice.create(any())).willReturn(CreateInvoiceResult.replayed(invoice()));

        MvcTestResult result = post(VALID_REQUEST);

        assertThat(result).hasStatus(HttpStatus.OK).doesNotContainHeader("Location");
        assertThat(result).bodyJson().extractingPath("$.id").isEqualTo("7f1c2a4e-0000-4000-8000-000000000001");
    }

    @Test
    @DisplayName("el mismo código con otro contenido responde 409")
    void referenceCodeConflictReturns409() {
        given(createInvoice.create(any())).willThrow(new ReferenceCodeConflictException("SALE-0001"));

        MvcTestResult result = post(VALID_REQUEST);

        assertProblem(result, HttpStatus.CONFLICT, "reference-code-conflict");
        assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("SALE-0001");
    }

    @Test
    @DisplayName("una regla de negocio incumplida responde 422 con el mensaje del dominio")
    void domainRuleReturns422() {
        given(createInvoice.create(any())).willThrow(InvalidIdentificationException.wrongCheckDigit("890903938", 1, 8));

        MvcTestResult result = post(VALID_REQUEST);

        assertProblem(result, HttpStatus.UNPROCESSABLE_CONTENT, "business-rule-violation");
        assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("890903938");
        assertThat(result).bodyJson().extractingPath("$.instance").isEqualTo(URL);
    }

    // ----- Validación de forma (400) -----

    @Test
    @DisplayName("los campos obligatorios faltantes responden 400 con la lista de campos y no llegan al caso de uso")
    void missingFieldsReturn400WithFieldErrors() {
        MvcTestResult result = post("""
                { "referenceCode": " ", "lines": [ { "code": "SKU-1", "name": "Producto", "unitPrice": 10,
                  "unit": "UNIT" } ], "paymentMethod": "CASH", "paymentTerms": { "type": "CASH" } }
                """);

        assertProblem(result, HttpStatus.BAD_REQUEST, "invalid-request");
        assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray()
                .containsExactlyInAnyOrder("referenceCode", "customer", "lines[0].quantity", "lines[0].taxes");
        verifyNoInteractions(createInvoice);
    }

    @Test
    @DisplayName("una venta de contado con fecha de vencimiento responde 400")
    void cashWithDueDateReturns400() {
        MvcTestResult result = post(VALID_REQUEST.replace("\"type\": \"CREDIT\"", "\"type\": \"CASH\""));

        assertProblem(result, HttpStatus.BAD_REQUEST, "invalid-request");
        assertThat(result).bodyJson().extractingPath("$.errors[0].message")
                .isEqualTo("dueDate solo se admite con type CREDIT");
    }

    @Test
    @DisplayName("más líneas que el tope anti-abuso responde 400")
    void tooManyLinesReturn400() {
        String line = "{ \"code\": \"A\", \"name\": \"A\", \"quantity\": 1, \"unitPrice\": 1, \"unit\": \"UNIT\", \"taxes\": [] }";
        String lines = String.join(",", java.util.Collections.nCopies(501, line));
        MvcTestResult result = post("""
                { "referenceCode": "S-1", "customer": { "documentType": "NIT", "documentNumber": "890903938",
                  "checkDigit": 8, "companyName": "C" }, "lines": [ %s ], "paymentMethod": "CASH",
                  "paymentTerms": { "type": "CASH" } }
                """.formatted(lines));

        assertProblem(result, HttpStatus.BAD_REQUEST, "invalid-request");
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("lines");
    }

    @Test
    @DisplayName("un JSON mal formado responde 400 sin exponer el mensaje del parser")
    void malformedJsonReturns400() {
        MvcTestResult result = post("{ \"referenceCode\": ");

        assertProblem(result, HttpStatus.BAD_REQUEST, "malformed-request");
        assertThat(result).bodyText().doesNotContain("Unexpected", "line:", "column");
    }

    @Test
    @DisplayName("un campo desconocido responde 400 e indica cuál es, en vez de ignorarlo")
    void unknownFieldReturns400() {
        MvcTestResult result = post(VALID_REQUEST.replace("\"observation\"", "\"observacion\""));

        assertProblem(result, HttpStatus.BAD_REQUEST, "malformed-request");
        assertThat(result).bodyJson().extractingPath("$.field").isEqualTo("observacion");
        verifyNoInteractions(createInvoice);
    }

    @Test
    @DisplayName("un valor de enum inexistente responde 400 e indica el campo")
    void unknownEnumValueReturns400() {
        MvcTestResult result = post(VALID_REQUEST.replace("\"unit\": \"BOX\"", "\"unit\": \"BARREL\""));

        assertProblem(result, HttpStatus.BAD_REQUEST, "malformed-request");
        assertThat(result).bodyJson().extractingPath("$.field").isEqualTo("lines[1].unit");
    }

    @Test
    @DisplayName("un tipo incorrecto (texto donde va un número) responde 400")
    void wrongTypeReturns400() {
        MvcTestResult result = post(VALID_REQUEST.replace("\"quantity\": 2", "\"quantity\": \"dos\""));

        assertProblem(result, HttpStatus.BAD_REQUEST, "malformed-request");
        assertThat(result).bodyJson().extractingPath("$.field").isEqualTo("lines[0].quantity");
    }

    @Test
    @DisplayName("un content-type que no es JSON responde 415 en Problem Details")
    void unsupportedMediaTypeReturns415() {
        MvcTestResult result = mvc.post().uri(URL).contentType(MediaType.TEXT_PLAIN).content("hola").exchange();

        assertThat(result).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    // ----- Consultar -----

    @Test
    @DisplayName("consultar una factura emitida incluye su documento electrónico y si el total oficial difiere")
    void getIssuedInvoiceIncludesElectronicDocument() {
        Invoice issued = pendingInvoice();
        issued.markAsIssued(electronicDocument("119000.01"));
        given(getInvoice.byId(issued.id().value())).willReturn(issued);

        MvcTestResult result = mvc.get().uri(URL + "/" + issued.id()).exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("ISSUED");
        assertThat(result).bodyJson().extractingPath("$.electronicDocument.number").isEqualTo("SETP990000001");
        assertThat(result).bodyJson().extractingPath("$.electronicDocument.validatedAt")
                .isEqualTo("2026-10-20T15:30:00Z");
        assertThat(result).bodyJson().extractingPath("$.electronicDocument.officialTotalDiscrepancy").isEqualTo(true);
    }

    @Test
    @DisplayName("un id que no es UUID responde 400")
    void invalidIdReturns400() {
        MvcTestResult result = mvc.get().uri(URL + "/no-es-un-uuid").exchange();

        assertProblem(result, HttpStatus.BAD_REQUEST, "invalid-parameter");
        assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("'id'");
        verifyNoInteractions(getInvoice);
    }

    @Test
    @DisplayName("una factura inexistente responde 404")
    void unknownInvoiceReturns404() {
        UUID id = UUID.randomUUID();
        given(getInvoice.byId(id)).willThrow(InvoiceNotFoundException.byId(id));

        assertProblem(mvc.get().uri(URL + "/" + id).exchange(), HttpStatus.NOT_FOUND, "invoice-not-found");
    }

    @Test
    @DisplayName("una factura se consulta por código de referencia y devuelve un objeto, no una lista")
    void getByReferenceCode() {
        given(getInvoice.byReferenceCode("SALE-0001")).willReturn(invoice());

        MvcTestResult result = mvc.get().uri(URL + "?referenceCode=SALE-0001").exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.referenceCode").isEqualTo("SALE-0001");
    }

    @Test
    @DisplayName("consultar la colección sin referenceCode responde 400")
    void missingReferenceCodeReturns400() {
        MvcTestResult result = mvc.get().uri(URL).exchange();

        assertProblem(result, HttpStatus.BAD_REQUEST, "missing-parameter");
        assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("referenceCode");
    }

    // ----- Emitir -----

    @Test
    @DisplayName("una emisión rechazada responde 200 con la factura REJECTED y su motivo")
    void rejectedIssueReturns200() {
        Invoice rejected = pendingInvoice();
        rejected.markAsRejected("Rechazo simulado");
        given(issueInvoice.issue(new IssueInvoiceCommand(rejected.id().value()))).willReturn(rejected);

        MvcTestResult result = mvc.post().uri(URL + "/" + rejected.id() + "/issue").exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("REJECTED");
        assertThat(result).bodyJson().extractingPath("$.rejectionReason").isEqualTo("Rechazo simulado");
    }

    @Test
    @DisplayName("emitir una factura ya rechazada responde 409")
    void alreadyRejectedReturns409() {
        UUID id = UUID.randomUUID();
        given(issueInvoice.issue(any())).willThrow(new InvoiceAlreadyRejectedException(id));

        assertProblem(mvc.post().uri(URL + "/" + id + "/issue").exchange(), HttpStatus.CONFLICT,
                "invoice-already-rejected");
    }

    @Test
    @DisplayName("una emisión simultánea que terminó distinto responde 409")
    void concurrentModificationReturns409() {
        UUID id = UUID.randomUUID();
        given(issueInvoice.issue(any())).willThrow(new InvoiceConcurrentModificationException(id, null));

        assertProblem(mvc.post().uri(URL + "/" + id + "/issue").exchange(), HttpStatus.CONFLICT,
                "concurrent-modification");
    }

    @Test
    @DisplayName("una transición de estado inválida que escape del dominio responde 409")
    void invalidStateTransitionReturns409() {
        UUID id = UUID.randomUUID();
        given(issueInvoice.issue(any()))
                .willThrow(new InvalidInvoiceStateTransitionException(id, "REJECTED", "ISSUED"));

        assertProblem(mvc.post().uri(URL + "/" + id + "/issue").exchange(), HttpStatus.CONFLICT,
                "invalid-state-transition");
    }

    @Test
    @DisplayName("si el proveedor está caído responde 503 con Retry-After y sin el detalle técnico")
    void providerUnavailableReturns503() {
        UUID id = UUID.randomUUID();
        given(issueInvoice.issue(any())).willThrow(new ElectronicInvoiceProviderUnavailableException(id,
                "Connection refused: factus-internal-host:8443", new RuntimeException("socket")));

        MvcTestResult result = mvc.post().uri(URL + "/" + id + "/issue").exchange();

        assertProblem(result, HttpStatus.SERVICE_UNAVAILABLE, "provider-unavailable");
        assertThat(result).hasHeader("Retry-After", "30");
        assertThat(result).bodyText().doesNotContain("Connection refused", "factus-internal-host", "socket");
    }

    @Test
    @DisplayName("un error inesperado responde 500 genérico, sin mensaje ni traza")
    void unexpectedErrorReturns500() {
        UUID id = UUID.randomUUID();
        given(getInvoice.byId(id)).willThrow(new IllegalStateException("could not execute statement [SQL secreto]"));

        MvcTestResult result = mvc.get().uri(URL + "/" + id).exchange();

        assertProblem(result, HttpStatus.INTERNAL_SERVER_ERROR, "internal-error");
        assertThat(result).bodyText().doesNotContain("SQL secreto", "IllegalStateException");
    }
}
