package com.facturaflow.invoicing.infrastructure.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.facturaflow.invoicing.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * De punta a punta: HTTP real (Tomcat en puerto aleatorio) → controlador → casos de uso →
 * JPA → PostgreSQL (Testcontainers), con el proveedor falso. Sin @Transactional: cada
 * petición hace commit como en producción.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class InvoiceApiEndToEndTest {

    private static final String URL = "/api/v1/invoices";

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
    }

    private Response create(String body) {
        return toResponse(client.post().uri(URL).contentType(MediaType.APPLICATION_JSON).body(body).exchange()
                .expectBody(String.class).returnResult());
    }

    private Response get(String uri) {
        return toResponse(client.get().uri(uri).exchange().expectBody(String.class).returnResult());
    }

    private Response issue(String id) {
        return toResponse(client.post().uri(URL + "/{id}/issue", id).exchange()
                .expectBody(String.class).returnResult());
    }

    private static Response toResponse(EntityExchangeResult<String> result) {
        return new Response(HttpStatus.valueOf(result.getStatus().value()), result.getResponseHeaders(),
                result.getResponseBody());
    }

    private static String cashRequest(String referenceCode, String observation) {
        return ApiExamples.CASH_WITH_IVA
                .replace("VENTA-2026-0001", referenceCode)
                .replace("Venta de mostrador", observation);
    }

    private long invoiceCount() {
        return jdbc.sql("SELECT count(*) FROM invoices").query(Long.class).single();
    }

    @Test
    @DisplayName("crear, consultar por id y por código, y emitir una factura con el proveedor falso")
    void createGetAndIssue() {
        Response created = create(ApiExamples.CASH_WITH_IVA);
        assertThat(created.status()).isEqualTo(HttpStatus.CREATED);
        String id = created.json("$.id");
        String location = created.headers().getLocation().toString();
        assertThat(location).endsWith(URL + "/" + id);
        assertThat((String) created.json("$.status")).isEqualTo("PENDING");
        assertThat((Double) created.json("$.totals.total")).isEqualTo(182070.0);   // 2 x 85.000 - 10 % + IVA 19 %

        Response byId = get(location);
        assertThat(byId.status()).isEqualTo(HttpStatus.OK);
        assertThat((String) byId.json("$.referenceCode")).isEqualTo("VENTA-2026-0001");

        Response byCode = get(URL + "?referenceCode=VENTA-2026-0001");
        assertThat((String) byCode.json("$.id")).isEqualTo(id);

        Response issued = issue(id);
        assertThat(issued.status()).isEqualTo(HttpStatus.OK);
        assertThat((String) issued.json("$.status")).isEqualTo("ISSUED");
        assertThat((String) issued.json("$.electronicDocument.number")).isEqualTo("FAKE-VENTA-2026-0001");
        assertThat((String) issued.json("$.electronicDocument.cufe")).startsWith("0000000000000000").hasSize(96);
        assertThat((Double) issued.json("$.electronicDocument.officialTotal")).isEqualTo(182070.0);
        assertThat((Boolean) issued.json("$.electronicDocument.officialTotalDiscrepancy")).isFalse();

        Response issuedAgain = issue(id);
        assertThat(issuedAgain.status()).isEqualTo(HttpStatus.OK);
        assertThat((String) issuedAgain.json("$.electronicDocument.validatedAt"))
                .isEqualTo(issued.json("$.electronicDocument.validatedAt"));
        assertThat((String) get(location).json("$.status")).isEqualTo("ISSUED");
    }

    @Test
    @DisplayName("el ejemplo de Swagger a crédito con IVA + INC es válido y produce el desglose por impuesto")
    void creditExampleIsValid() {
        Response created = create(ApiExamples.CREDIT_WITH_IVA_AND_INC);

        assertThat(created.status()).isEqualTo(HttpStatus.CREATED);
        assertThat((String) created.json("$.paymentTerms.type")).isEqualTo("CREDIT");
        assertThat((List<String>) created.json("$.totals.taxes[*].type")).containsExactly("IVA", "INC");
        assertThat((Double) created.json("$.totals.taxes[0].amount")).isEqualTo(2441500.0);
        assertThat((Double) created.json("$.totals.taxes[1].amount")).isEqualTo(1000000.0);
        assertThat((Double) created.json("$.totals.total")).isEqualTo(16291500.0);
    }

    @Test
    @DisplayName("con SIMULAR_RECHAZO la emisión responde 200 con la factura REJECTED, y volver a emitirla da 409")
    void simulatedRejection() {
        String id = create(cashRequest("VENTA-R", "SIMULAR_RECHAZO")).json("$.id");

        Response rejected = issue(id);
        assertThat(rejected.status()).isEqualTo(HttpStatus.OK);
        assertThat((String) rejected.json("$.status")).isEqualTo("REJECTED");
        assertThat((String) rejected.json("$.rejectionReason")).contains("SIMULAR_RECHAZO");

        Response again = issue(id);
        assertThat(again.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat((String) again.json("$.type")).isEqualTo("urn:facturaflow:problem:invoice-already-rejected");
    }

    @Test
    @DisplayName("con SIMULAR_CAIDA la emisión responde 503 con Retry-After y la factura sigue PENDING")
    void simulatedOutage() {
        String id = create(cashRequest("VENTA-C", "SIMULAR_CAIDA")).json("$.id");

        Response outage = issue(id);

        assertThat(outage.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(outage.headers().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("30");
        assertThat(outage.headers().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat((String) get(URL + "/" + id).json("$.status")).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("crear dos veces la misma venta devuelve la misma factura; con otro contenido responde 409")
    void doubleCreation() {
        Response first = create(cashRequest("VENTA-D", "Primera"));
        Response retry = create(cashRequest("VENTA-D", "Primera"));
        Response conflicting = create(cashRequest("VENTA-D", "Otra cosa"));

        assertThat(first.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.status()).isEqualTo(HttpStatus.OK);
        assertThat(retry.headers().getLocation()).isNull();
        assertThat((String) retry.json("$.id")).isEqualTo(first.json("$.id"));
        assertThat(conflicting.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat((String) conflicting.json("$.type")).isEqualTo("urn:facturaflow:problem:reference-code-conflict");
        assertThat(invoiceCount()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT request_hash FROM invoices").query(String.class).single()).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("dos creaciones simultáneas de la misma venta producen una sola factura: un 201 y un 200")
    void concurrentCreation() throws Exception {
        int rounds = 5;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            for (int round = 0; round < rounds; round++) {
                String body = cashRequest("VENTA-SIM-" + round, "Simultánea");
                CountDownLatch start = new CountDownLatch(1);
                Callable<Response> request = () -> {
                    start.await();
                    return create(body);
                };
                List<Future<Response>> futures = List.of(executor.submit(request), executor.submit(request));
                start.countDown();

                List<HttpStatus> statuses = new ArrayList<>();
                List<String> ids = new ArrayList<>();
                for (Future<Response> future : futures) {
                    statuses.add(future.get().status());
                    ids.add(future.get().json("$.id"));
                }
                assertThat(statuses).as("ronda %d", round).containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.OK);
                assertThat(ids.get(0)).isEqualTo(ids.get(1));
            }
        }
        assertThat(invoiceCount()).isEqualTo(rounds);
    }

    @Test
    @DisplayName("una regla de negocio incumplida responde 422 y no guarda nada")
    void businessRuleViolation() {
        Response response = create(ApiExamples.CASH_WITH_IVA.replace("\"checkDigit\": 8", "\"checkDigit\": 1"));

        assertThat(response.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat((String) response.json("$.type")).isEqualTo("urn:facturaflow:problem:business-rule-violation");
        assertThat(invoiceCount()).isZero();
    }

    @Test
    @DisplayName("una ruta inexistente responde 404 en Problem Details")
    void unknownRoute() {
        Response response = get("/api/v1/nada");

        assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.headers().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    @DisplayName("la documentación OpenAPI y Swagger UI están publicadas, con los ejemplos y las palabras de simulación")
    void openApiIsPublished() {
        Response docs = get("/v3/api-docs");

        assertThat(docs.status()).isEqualTo(HttpStatus.OK);
        assertThat(docs.body()).contains("/api/v1/invoices/{id}/issue", "Contado con IVA", "Crédito con IVA + INC",
                "SIMULAR_RECHAZO", "SIMULAR_CAIDA");
        assertThat(get("/swagger-ui/index.html").status()).isEqualTo(HttpStatus.OK);
    }
}
