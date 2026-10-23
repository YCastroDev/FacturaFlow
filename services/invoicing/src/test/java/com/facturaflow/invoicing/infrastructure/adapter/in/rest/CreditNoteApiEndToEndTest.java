package com.facturaflow.invoicing.infrastructure.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.facturaflow.invoicing.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Notas crédito de punta a punta (ADR 0009): HTTP real → casos de uso → JPA → PostgreSQL, con el
 * proveedor falso. La factura es el ejemplo "Contado con IVA": 2 x 85.000 - 10 % + IVA 19 % = 182.070.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class CreditNoteApiEndToEndTest {

    private static final String INVOICES = "/api/v1/invoices";
    private static final String NOTES = "/api/v1/credit-notes";

    @Autowired
    private RestTestClient client;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("TRUNCATE TABLE invoices CASCADE").update();
    }

    private record Response(HttpStatus status, HttpHeaders headers, String body) {

        <T> T json(String path) {
            return JsonPath.read(body, path);
        }

        double number(String path) {
            return ((Number) JsonPath.read(body, path)).doubleValue();
        }
    }

    private Response post(String uri, String body) {
        var request = client.post().uri(uri);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return toResponse(request.exchange().expectBody(String.class).returnResult());
    }

    private Response get(String uri) {
        return toResponse(client.get().uri(uri).exchange().expectBody(String.class).returnResult());
    }

    private static Response toResponse(EntityExchangeResult<String> result) {
        return new Response(HttpStatus.valueOf(result.getStatus().value()), result.getResponseHeaders(),
                result.getResponseBody());
    }

    private String issuedInvoice(String referenceCode) {
        String id = post(INVOICES, ApiExamples.CASH_WITH_IVA.replace("VENTA-2026-0001", referenceCode)).json("$.id");
        assertThat((String) post(INVOICES + "/" + id + "/issue", null).json("$.status")).isEqualTo("ISSUED");
        return id;
    }

    private static String partialReturn(String referenceCode, String quantity, String observation) {
        return """
                { "referenceCode": "%s", "reason": "PARTIAL_RETURN",
                  "lines": [ { "lineNumber": 1, "quantity": %s } ], "observation": "%s" }
                """.formatted(referenceCode, quantity, observation);
    }

    @Test
    @DisplayName("devolución parcial, emisión, saldo, PDF y anulación de lo pendiente sobre una factura emitida")
    void partialReturnThenAnnulment() {
        String invoiceId = issuedInvoice("VENTA-NC-1");
        assertThat(get(INVOICES + "/" + invoiceId).number("$.credits.balance")).isEqualTo(182070.0);

        // 1. Devolución de 1 de las 2 unidades
        Response created = post(INVOICES + "/" + invoiceId + "/credit-notes", ApiExamples.PARTIAL_RETURN);
        assertThat(created.status()).isEqualTo(HttpStatus.CREATED);
        String noteId = created.json("$.id");
        assertThat(created.headers().getLocation().toString()).endsWith(NOTES + "/" + noteId);
        assertThat((String) created.json("$.status")).isEqualTo("PENDING");
        assertThat(created.number("$.totals.total")).isEqualTo(91035.0);

        Response afterCreate = get(INVOICES + "/" + invoiceId);
        assertThat((String) afterCreate.json("$.status")).isEqualTo("ISSUED");   // la factura no cambia de estado
        assertThat(afterCreate.number("$.credits.pendingTotal")).isEqualTo(91035.0);
        assertThat(afterCreate.number("$.credits.lines[0].remainingQuantity")).isEqualTo(1.0);

        // 2. Ya no caben 2 unidades más
        Response tooMuch = post(INVOICES + "/" + invoiceId + "/credit-notes", partialReturn("DEV-X", "2", "x"));
        assertThat(tooMuch.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat((String) tooMuch.json("$.detail")).contains("solo quedan 1.00");

        // 3. Emitir la nota y descargar su PDF
        Response issued = post(NOTES + "/" + noteId + "/issue", null);
        assertThat((String) issued.json("$.status")).isEqualTo("ISSUED");
        assertThat((String) issued.json("$.electronicDocument.number")).isEqualTo("FAKE-NC-DEV-2026-0001");
        assertThat((String) issued.json("$.electronicDocument.cude")).startsWith("0000000000000000").hasSize(96);
        assertThat(post(NOTES + "/" + noteId + "/issue", null).status()).isEqualTo(HttpStatus.OK);   // re-emitir
        EntityExchangeResult<byte[]> pdf = client.get().uri(NOTES + "/{id}/pdf", noteId).exchange()
                .expectBody(byte[].class).returnResult();
        assertThat(pdf.getStatus().value()).isEqualTo(200);
        assertThat(pdf.getResponseHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(new String(pdf.getResponseBody(), 0, 5)).isEqualTo("%PDF-");

        Response afterIssue = get(INVOICES + "/" + invoiceId);
        assertThat(afterIssue.number("$.credits.issuedTotal")).isEqualTo(91035.0);
        assertThat(afterIssue.number("$.credits.pendingTotal")).isEqualTo(0.0);
        assertThat(afterIssue.number("$.credits.balance")).isEqualTo(91035.0);

        // 4. Anular: acredita solo lo pendiente (1 unidad)
        Response annulment = post(INVOICES + "/" + invoiceId + "/credit-notes", ApiExamples.TOTAL_ANNULMENT);
        assertThat(annulment.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(annulment.number("$.lines[0].quantity")).isEqualTo(1.0);
        post(NOTES + "/" + annulment.json("$.id") + "/issue", null);

        Response annulled = get(INVOICES + "/" + invoiceId);
        assertThat((String) annulled.json("$.status")).isEqualTo("ISSUED");
        assertThat(annulled.number("$.credits.issuedTotal")).isEqualTo(182070.0);
        assertThat(annulled.number("$.credits.balance")).isEqualTo(0.0);

        Response again = post(INVOICES + "/" + invoiceId + "/credit-notes",
                ApiExamples.TOTAL_ANNULMENT.replace("ANU-2026-0001", "ANU-2026-0002"));
        assertThat(again.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        List<String> listed = get(INVOICES + "/" + invoiceId + "/credit-notes").json("$[*].referenceCode");
        assertThat(listed).containsExactly("DEV-2026-0001", "ANU-2026-0001");
    }

    @Test
    @DisplayName("la creación es idempotente por referenceCode: 200 si es idéntica, 409 si cambia el contenido")
    void creationIsIdempotent() {
        String invoiceId = issuedInvoice("VENTA-NC-2");
        String url = INVOICES + "/" + invoiceId + "/credit-notes";
        String first = post(url, ApiExamples.PARTIAL_RETURN).json("$.id");

        Response retry = post(url, ApiExamples.PARTIAL_RETURN);
        assertThat(retry.status()).isEqualTo(HttpStatus.OK);
        assertThat((String) retry.json("$.id")).isEqualTo(first);

        Response conflict = post(url, ApiExamples.PARTIAL_RETURN.replace("\"quantity\": 1", "\"quantity\": 2"));
        assertThat(conflict.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat((String) conflict.json("$.type")).isEqualTo("urn:facturaflow:problem:credit-note-reference-code-conflict");
    }

    @Test
    @DisplayName("con el proveedor falso: SIMULAR_CAIDA deja la nota PENDING (503) y SIMULAR_RECHAZO libera sus cantidades")
    void simulatedOutageAndRejection() {
        String invoiceId = issuedInvoice("VENTA-NC-3");
        String url = INVOICES + "/" + invoiceId + "/credit-notes";

        String outage = post(url, partialReturn("DEV-CAIDA", "1", "SIMULAR_CAIDA")).json("$.id");
        Response unavailable = post(NOTES + "/" + outage + "/issue", null);
        assertThat(unavailable.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat((String) unavailable.json("$.detail")).contains("La nota crédito sigue PENDING");
        assertThat((String) get(NOTES + "/" + outage).json("$.status")).isEqualTo("PENDING");

        String rejected = post(url, partialReturn("DEV-RECHAZO", "1", "SIMULAR_RECHAZO")).json("$.id");
        assertThat(get(INVOICES + "/" + invoiceId).number("$.credits.lines[0].remainingQuantity")).isEqualTo(0.0);
        Response rejection = post(NOTES + "/" + rejected + "/issue", null);
        assertThat((String) rejection.json("$.status")).isEqualTo("REJECTED");
        assertThat(get(INVOICES + "/" + invoiceId).number("$.credits.lines[0].remainingQuantity")).isEqualTo(1.0);
        assertThat(post(NOTES + "/" + rejected + "/issue", null).status()).isEqualTo(HttpStatus.CONFLICT);

        assertThat(post(url, partialReturn("DEV-NUEVA", "1", "otra")).status()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("una factura PENDING no se acredita (409) y una inexistente responde 404")
    void onlyIssuedInvoicesAreCreditable() {
        String pending = post(INVOICES, ApiExamples.CASH_WITH_IVA).json("$.id");

        Response notIssued = post(INVOICES + "/" + pending + "/credit-notes", ApiExamples.PARTIAL_RETURN);
        assertThat(notIssued.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat((String) notIssued.json("$.type")).isEqualTo("urn:facturaflow:problem:invoice-not-issued");
        assertThat(get(INVOICES + "/" + pending).body()).doesNotContain("credits");

        Response unknown = post(INVOICES + "/7f1c2a4e-0000-4000-8000-00000000dead/credit-notes",
                ApiExamples.PARTIAL_RETURN);
        assertThat(unknown.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(NOTES + "/7f1c2a4e-0000-4000-8000-00000000dead").status()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
