package com.facturaflow.invoicing.infrastructure.adapter.in.rest;

import static com.facturaflow.invoicing.domain.model.CreditNoteFixtures.annulment;
import static com.facturaflow.invoicing.domain.model.CreditNoteFixtures.credit;
import static com.facturaflow.invoicing.domain.model.CreditNoteFixtures.creditNoteDocument;
import static com.facturaflow.invoicing.domain.model.CreditNoteFixtures.issuedInvoiceToCredit;
import static com.facturaflow.invoicing.domain.model.CreditNoteFixtures.partialReturn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.facturaflow.invoicing.application.exception.CreditNoteAlreadyRejectedException;
import com.facturaflow.invoicing.application.exception.CreditNoteConcurrentModificationException;
import com.facturaflow.invoicing.application.exception.CreditNoteNotFoundException;
import com.facturaflow.invoicing.application.exception.CreditNoteNotIssuedException;
import com.facturaflow.invoicing.application.exception.CreditNoteReferenceCodeConflictException;
import com.facturaflow.invoicing.application.exception.DuplicateCreditNoteReferenceCodeException;
import com.facturaflow.invoicing.application.exception.ElectronicInvoiceProviderUnavailableException;
import com.facturaflow.invoicing.application.exception.InvoiceConcurrentModificationException;
import com.facturaflow.invoicing.application.exception.InvoiceNotFoundException;
import com.facturaflow.invoicing.application.port.in.CreateCreditNoteCommand;
import com.facturaflow.invoicing.application.port.in.CreateCreditNoteResult;
import com.facturaflow.invoicing.application.port.in.CreateCreditNoteUseCase;
import com.facturaflow.invoicing.application.port.in.GetCreditNotePdfUseCase;
import com.facturaflow.invoicing.application.port.in.GetCreditNoteUseCase;
import com.facturaflow.invoicing.application.port.in.IssueCreditNoteUseCase;
import com.facturaflow.invoicing.application.port.out.InvoicePdf;
import com.facturaflow.invoicing.domain.exception.InvalidCreditNoteException;
import com.facturaflow.invoicing.domain.exception.InvoiceNotCreditableException;
import com.facturaflow.invoicing.domain.model.CreditBalance;
import com.facturaflow.invoicing.domain.model.CreditNote;
import com.facturaflow.invoicing.domain.model.CreditNoteReason;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.domain.model.Money;
import java.math.BigDecimal;
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

/** Capa web de las notas crédito, como {@code InvoiceControllerTest}: casos de uso simulados. */
@WebMvcTest(CreditNoteController.class)
class CreditNoteControllerTest {

    private static final Invoice INVOICE = issuedInvoiceToCredit();
    private static final String INVOICE_NOTES_URL = "/api/v1/invoices/" + INVOICE.id() + "/credit-notes";
    private static final String NOTES_URL = "/api/v1/credit-notes";

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private CreateCreditNoteUseCase createCreditNote;

    @MockitoBean
    private GetCreditNoteUseCase getCreditNote;

    @MockitoBean
    private IssueCreditNoteUseCase issueCreditNote;

    @MockitoBean
    private GetCreditNotePdfUseCase getCreditNotePdf;

    private static CreditNote partialNote() {
        return partialReturn(CreditBalance.of(INVOICE, List.of()), "DEV-1", credit(1, "1"));
    }

    private MvcTestResult post(String body) {
        return mvc.post().uri(INVOICE_NOTES_URL).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private static void assertProblem(MvcTestResult result, HttpStatus status, String slug) {
        assertThat(result).hasStatus(status).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo("urn:facturaflow:problem:" + slug);
        assertThat(result).bodyText().doesNotContain("trace", "exception", "Exception", "hibernate", "jackson");
    }

    // ----- Crear -----

    @Test
    @DisplayName("crear una devolución parcial responde 201 con Location y los montos de la línea acreditada")
    void createReturns201() {
        CreditNote note = partialNote();
        given(createCreditNote.create(any())).willReturn(CreateCreditNoteResult.created(note));

        MvcTestResult result = post(ApiExamples.PARTIAL_RETURN);

        assertThat(result).hasStatus(HttpStatus.CREATED)
                .hasHeader("Location", "http://localhost/api/v1/credit-notes/" + note.id());
        assertThat(result).bodyJson().extractingPath("$.invoiceId").isEqualTo(INVOICE.id().toString());
        assertThat(result).bodyJson().extractingPath("$.reason").isEqualTo("PARTIAL_RETURN");
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("PENDING");
        assertThat(result).bodyJson().extractingPath("$.lines[0].invoiceLineNumber").isEqualTo(1);
        assertThat(result).bodyJson().extractingPath("$.lines[0].quantity").isEqualTo(1.0);
        assertThat(result).bodyJson().extractingPath("$.lines[0].discountAmount").isEqualTo(2500.0);
        assertThat(result).bodyJson().extractingPath("$.totals.taxes[1].type").isEqualTo("INC");
        assertThat(result).bodyJson().extractingPath("$.totals.taxes[1].amount").isEqualTo(2466.67);
        assertThat(result).bodyText().contains("\"total\":39158.33");
        assertThat(result).bodyJson().doesNotHavePath("$.electronicDocument");
    }

    @Test
    @DisplayName("la petición se traduce al comando con la factura de la ruta; el cliente nunca viene en la petición")
    void mapsRequestToCommand() {
        given(createCreditNote.create(any())).willReturn(CreateCreditNoteResult.created(partialNote()));

        post(ApiExamples.PARTIAL_RETURN);

        ArgumentCaptor<CreateCreditNoteCommand> captor = ArgumentCaptor.forClass(CreateCreditNoteCommand.class);
        verify(createCreditNote).create(captor.capture());
        CreateCreditNoteCommand command = captor.getValue();
        assertThat(command.invoiceId()).isEqualTo(INVOICE.id().value());
        assertThat(command.referenceCode()).isEqualTo("DEV-2026-0001");
        assertThat(command.reason()).isEqualTo(CreditNoteReason.PARTIAL_RETURN);
        assertThat(command.lines()).containsExactly(new CreateCreditNoteCommand.Line(1, BigDecimal.ONE));
        assertThat(command.observation()).isEqualTo("El cliente devolvió un teclado");
    }

    @Test
    @DisplayName("una anulación total sin líneas se traduce a un comando con la lista vacía")
    void annulmentWithoutLines() {
        given(createCreditNote.create(any())).willReturn(CreateCreditNoteResult.created(
                annulment(CreditBalance.of(INVOICE, List.of()), "ANU-2026-0001")));

        MvcTestResult result = post(ApiExamples.TOTAL_ANNULMENT);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.lines").asArray().hasSize(2);
        ArgumentCaptor<CreateCreditNoteCommand> captor = ArgumentCaptor.forClass(CreateCreditNoteCommand.class);
        verify(createCreditNote).create(captor.capture());
        assertThat(captor.getValue().reason()).isEqualTo(CreditNoteReason.TOTAL_ANNULMENT);
        assertThat(captor.getValue().lines()).isEmpty();
    }

    @Test
    @DisplayName("un reintento idéntico responde 200 con la nota existente")
    void identicalRetryReturns200() {
        given(createCreditNote.create(any())).willReturn(CreateCreditNoteResult.replayed(partialNote()));

        assertThat(post(ApiExamples.PARTIAL_RETURN)).hasStatus(HttpStatus.OK);
    }

    @Test
    @DisplayName("faltar el motivo o la cantidad de una línea responde 400 con los campos, sin llamar al caso de uso")
    void missingFieldsReturn400() {
        MvcTestResult result = post("""
                { "referenceCode": "DEV-1", "lines": [ { "lineNumber": 1 } ] }""");

        assertProblem(result, HttpStatus.BAD_REQUEST, "invalid-request");
        assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray()
                .containsExactlyInAnyOrder("reason", "lines[0].quantity");
        verifyNoInteractions(createCreditNote);
    }

    @Test
    @DisplayName("un campo que no existe (p. ej. intentar enviar el cliente) responde 400")
    void unknownFieldReturns400() {
        MvcTestResult result = post("""
                { "referenceCode": "DEV-1", "reason": "TOTAL_ANNULMENT", "customer": { "names": "Otro" } }""");

        assertProblem(result, HttpStatus.BAD_REQUEST, "malformed-request");
        verifyNoInteractions(createCreditNote);
    }

    @Test
    @DisplayName("acreditar una factura inexistente responde 404")
    void unknownInvoiceReturns404() {
        given(createCreditNote.create(any())).willThrow(InvoiceNotFoundException.byId(INVOICE.id().value()));

        assertProblem(post(ApiExamples.PARTIAL_RETURN), HttpStatus.NOT_FOUND, "invoice-not-found");
    }

    @Test
    @DisplayName("acreditar una factura que no está ISSUED responde 409")
    void notCreditableInvoiceReturns409() {
        given(createCreditNote.create(any())).willThrow(new InvoiceNotCreditableException(INVOICE.id().value(),
                "PENDING"));

        MvcTestResult result = post(ApiExamples.PARTIAL_RETURN);

        assertProblem(result, HttpStatus.CONFLICT, "invoice-not-issued");
        assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("solo se pueden acreditar");
    }

    @Test
    @DisplayName("acreditar más de lo que queda responde 422 con el mensaje del dominio")
    void exceedingReturns422() {
        given(createCreditNote.create(any())).willThrow(InvalidCreditNoteException.exceedsRemaining(1,
                new BigDecimal("2.00"), new BigDecimal("1.00")));

        MvcTestResult result = post(ApiExamples.PARTIAL_RETURN);

        assertProblem(result, HttpStatus.UNPROCESSABLE_CONTENT, "business-rule-violation");
        assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("solo quedan 1.00");
    }

    @Test
    @DisplayName("el mismo código con otro contenido responde 409")
    void referenceCodeConflictReturns409() {
        given(createCreditNote.create(any())).willThrow(new CreditNoteReferenceCodeConflictException("DEV-1"));

        assertProblem(post(ApiExamples.PARTIAL_RETURN), HttpStatus.CONFLICT, "credit-note-reference-code-conflict");
    }

    @Test
    @DisplayName("red de seguridad: un código duplicado que llegara hasta aquí también responde 409")
    void duplicateReferenceCodeReturns409() {
        given(createCreditNote.create(any())).willThrow(new DuplicateCreditNoteReferenceCodeException("DEV-1", null));

        assertProblem(post(ApiExamples.PARTIAL_RETURN), HttpStatus.CONFLICT, "credit-note-reference-code-conflict");
    }

    @Test
    @DisplayName("conflictos persistentes con otras notas simultáneas sobre la factura responden 409")
    void persistentConflictsReturn409() {
        given(createCreditNote.create(any())).willThrow(new InvoiceConcurrentModificationException(
                INVOICE.id().value(), null));

        assertProblem(post(ApiExamples.PARTIAL_RETURN), HttpStatus.CONFLICT, "concurrent-modification");
    }

    // ----- Consultar -----

    @Test
    @DisplayName("las notas de una factura se listan en un arreglo")
    void listsByInvoice() {
        given(getCreditNote.byInvoice(INVOICE.id().value())).willReturn(List.of(partialNote()));

        MvcTestResult result = mvc.get().uri(INVOICE_NOTES_URL).exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$[0].referenceCode").isEqualTo("DEV-1");
    }

    @Test
    @DisplayName("listar las notas de una factura inexistente responde 404")
    void listOfUnknownInvoiceReturns404() {
        given(getCreditNote.byInvoice(INVOICE.id().value()))
                .willThrow(InvoiceNotFoundException.byId(INVOICE.id().value()));

        assertProblem(mvc.get().uri(INVOICE_NOTES_URL).exchange(), HttpStatus.NOT_FOUND, "invoice-not-found");
    }

    @Test
    @DisplayName("una nota ISSUED muestra su CUDE (no un CUFE) y sus enlaces")
    void getIssuedNote() {
        CreditNote note = partialNote();
        note.markAsIssued(creditNoteDocument(Money.of("39158.33")));
        given(getCreditNote.byId(note.id().value())).willReturn(note);

        MvcTestResult result = mvc.get().uri(NOTES_URL + "/" + note.id()).exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.electronicDocument.number").isEqualTo("NC1661");
        assertThat(result).bodyJson().extractingPath("$.electronicDocument.cude").asString().hasSize(96);
        assertThat(result).bodyJson().doesNotHavePath("$.electronicDocument.cufe");
        assertThat(result).bodyJson().extractingPath("$.electronicDocument.officialTotalDiscrepancy").isEqualTo(false);
    }

    @Test
    @DisplayName("una nota inexistente responde 404")
    void unknownNoteReturns404() {
        UUID id = UUID.randomUUID();
        given(getCreditNote.byId(id)).willThrow(new CreditNoteNotFoundException(id));

        assertProblem(mvc.get().uri(NOTES_URL + "/" + id).exchange(), HttpStatus.NOT_FOUND, "credit-note-not-found");
    }

    // ----- Emitir y PDF -----

    @Test
    @DisplayName("una emisión rechazada responde 200 con la nota REJECTED y su motivo")
    void rejectedIssueReturns200() {
        CreditNote note = partialNote();
        note.markAsRejected("Rechazo simulado");
        given(issueCreditNote.issue(any())).willReturn(note);

        MvcTestResult result = mvc.post().uri(NOTES_URL + "/" + note.id() + "/issue").exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("REJECTED");
        assertThat(result).bodyJson().extractingPath("$.rejectionReason").isEqualTo("Rechazo simulado");
    }

    @Test
    @DisplayName("emitir una nota ya rechazada responde 409")
    void alreadyRejectedReturns409() {
        UUID id = UUID.randomUUID();
        given(issueCreditNote.issue(any())).willThrow(new CreditNoteAlreadyRejectedException(id));

        assertProblem(mvc.post().uri(NOTES_URL + "/" + id + "/issue").exchange(), HttpStatus.CONFLICT,
                "credit-note-already-rejected");
    }

    @Test
    @DisplayName("una emisión simultánea que terminó distinto responde 409")
    void concurrentIssueReturns409() {
        UUID id = UUID.randomUUID();
        given(issueCreditNote.issue(any())).willThrow(new CreditNoteConcurrentModificationException(id, null));

        MvcTestResult result = mvc.post().uri(NOTES_URL + "/" + id + "/issue").exchange();

        assertProblem(result, HttpStatus.CONFLICT, "concurrent-modification");
        assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("La nota crédito " + id);
    }

    @Test
    @DisplayName("si el proveedor no está disponible responde 503 con Retry-After y dice que la NOTA sigue PENDING")
    void providerUnavailableReturns503() {
        UUID id = UUID.randomUUID();
        given(issueCreditNote.issue(any())).willThrow(ElectronicInvoiceProviderUnavailableException.forCreditNote(id,
                "Connection refused: factus-internal-host:8443", null));

        MvcTestResult result = mvc.post().uri(NOTES_URL + "/" + id + "/issue").exchange();

        assertProblem(result, HttpStatus.SERVICE_UNAVAILABLE, "provider-unavailable");
        assertThat(result).hasHeader("Retry-After", "30");
        assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("La nota crédito sigue PENDING");
        assertThat(result).bodyText().doesNotContain("factus-internal-host");
    }

    @Test
    @DisplayName("el PDF de una nota emitida se descarga como adjunto")
    void downloadsPdf() {
        UUID id = UUID.randomUUID();
        given(getCreditNotePdf.pdf(id)).willReturn(new InvoicePdf("nc10007890020002600002877", new byte[] {'%', 'P'}));

        MvcTestResult result = mvc.get().uri(NOTES_URL + "/" + id + "/pdf").exchange();

        assertThat(result).hasStatus(HttpStatus.OK).hasContentType(MediaType.APPLICATION_PDF)
                .hasHeader("Content-Disposition", "attachment; filename=\"nc10007890020002600002877.pdf\"");
    }

    @Test
    @DisplayName("el PDF de una nota que no está ISSUED responde 409")
    void pdfOfNotIssuedReturns409() {
        UUID id = UUID.randomUUID();
        given(getCreditNotePdf.pdf(id)).willThrow(new CreditNoteNotIssuedException(id, "PENDING"));

        assertProblem(mvc.get().uri(NOTES_URL + "/" + id + "/pdf").exchange(), HttpStatus.CONFLICT,
                "credit-note-not-issued");
    }
}
