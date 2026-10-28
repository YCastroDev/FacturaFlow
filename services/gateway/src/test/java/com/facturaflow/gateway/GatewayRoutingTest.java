package com.facturaflow.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * El gateway real (servidor en un puerto aleatorio) contra tres servicios falsos (WireMock): cada ruta llega a su
 * servicio con método, cuerpo, query y cabeceras; las respuestas vuelven intactas; lo que no tiene ruta y los servicios
 * caídos o lentos responden Problem Details del gateway (ADR 0017).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestSecurityConfiguration.class)
class GatewayRoutingTest {

    private static final WireMockServer INVOICING = start();
    private static final WireMockServer CATALOG = start();
    private static final WireMockServer SALES = start();
    private static final Map<String, WireMockServer> SERVICES =
            Map.of("invoicing", INVOICING, "catalog", CATALOG, "sales", SALES);

    /** Holgado para las respuestas normales; el test del timeout usa una respuesta mucho más lenta. */
    private static final int READ_TIMEOUT_SECONDS = 3;
    private static final int SLOW_RESPONSE_MILLIS = 8_000;

    @Autowired
    private RestTestClient client;

    @DynamicPropertySource
    static void services(DynamicPropertyRegistry registry) {
        registry.add("GATEWAY_INVOICING_URI", INVOICING::baseUrl);
        registry.add("GATEWAY_CATALOG_URI", CATALOG::baseUrl);
        registry.add("GATEWAY_SALES_URI", SALES::baseUrl);
        registry.add("spring.http.clients.read-timeout", () -> READ_TIMEOUT_SECONDS + "s");
    }

    private static WireMockServer start() {
        WireMockServer server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
        return server;
    }

    @AfterAll
    static void stop() {
        SERVICES.values().forEach(WireMockServer::stop);
    }

    @BeforeEach
    void stubEveryServiceWithItsName() {
        SERVICES.forEach((name, server) -> {
            server.resetAll();
            server.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"service\":\"" + name + "\"}")));
        });
    }

    @ParameterizedTest(name = "{0} {1} → {2}")
    @CsvSource({
            "GET,  /api/v1/invoices/7c3f2a10-0000-4000-8000-000000000001,              invoicing",
            "POST, /api/v1/invoices,                                                   invoicing",
            "POST, /api/v1/invoices/7c3f2a10-0000-4000-8000-000000000001/issue,        invoicing",
            "GET,  /api/v1/invoices/by-reference/VENTA-1,                              invoicing",
            "GET,  /api/v1/invoices/7c3f2a10-0000-4000-8000-000000000001/pdf,          invoicing",
            "POST, /api/v1/invoices/7c3f2a10-0000-4000-8000-000000000001/credit-notes, invoicing",
            "GET,  /api/v1/credit-notes/9a000000-0000-4000-8000-000000000002,          invoicing",
            "POST, /api/v1/credit-notes/9a000000-0000-4000-8000-000000000002/issue,    invoicing",
            "GET,  /api/v1/products,                                                   catalog",
            "GET,  /api/v1/products/by-code/CAFE-500,                                  catalog",
            "POST, /api/v1/products/lookup,                                            catalog",
            "PUT,  /api/v1/products/9f000000-0000-4000-8000-0000000000c1,              catalog",
            "GET,  /api/v1/customers/by-document/NIT/890903938,                        catalog",
            "POST, /api/v1/customers/c0000000-0000-4000-8000-000000000001/deactivate,  catalog",
            "POST, /api/v1/sales,                                                      sales",
            "GET,  /api/v1/sales/5a000000-0000-4000-8000-000000000003,                 sales",
            "GET,  /api/v1/sales/by-reference/VENTA-1,                                 sales"
    })
    @DisplayName("Cada ruta llega al servicio dueño del recurso, con el mismo método y la misma ruta")
    void routesToOwningService(String method, String path, String service) {
        EntityExchangeResult<String> result = client.method(HttpMethod.valueOf(method)).uri(path)
                .exchange().expectBody(String.class).returnResult();

        assertThat(result.getStatus().value()).isEqualTo(200);
        assertThat((String) JsonPath.read(result.getResponseBody(), "$.service")).isEqualTo(service);
        SERVICES.forEach((name, server) -> assertThat(server.getAllServeEvents())
                .as(name).hasSize(name.equals(service) ? 1 : 0));
        assertThat(SERVICES.get(service).getAllServeEvents().getFirst().getRequest())
                .satisfies(request -> {
                    assertThat(request.getMethod().getName()).isEqualTo(method);
                    assertThat(request.getUrl()).isEqualTo(path);
                });
    }

    @Test
    @DisplayName("El cuerpo, la query y las cabeceras (también el token, intacto) llegan al servicio sin cambios")
    void forwardsBodyQueryAndHeaders() {
        client.post().uri("/api/v1/sales?dryRun=false")
                .contentType(MediaType.APPLICATION_JSON)
                .header("If-Match", "\"3\"")
                .body("{\"referenceCode\":\"VENTA-1\"}")
                .exchange().expectStatus().isOk();

        SALES.verify(postRequestedFor(urlEqualTo("/api/v1/sales?dryRun=false"))
                .withHeader("Content-Type", containing("application/json"))
                .withHeader("Authorization", equalTo("Bearer " + TestSecurityConfiguration.DEFAULT_TOKEN))
                .withHeader("If-Match", equalTo("\"3\""))
                .withRequestBody(equalToJson("{\"referenceCode\":\"VENTA-1\"}")));
    }

    @Test
    @DisplayName("El cliente no puede falsear X-Forwarded-For ante los servicios: el gateway la quita")
    void dropsSpoofedForwardedHeaders() {
        client.get().uri("/api/v1/products").header("X-Forwarded-For", "10.0.0.66").header("Forwarded", "for=10.0.0.66")
                .exchange().expectStatus().isOk();

        CATALOG.verify(anyRequestedFor(urlEqualTo("/api/v1/products"))
                .withHeader("X-Forwarded-For", absent())
                .withHeader("Forwarded", absent()));
    }

    @Test
    @DisplayName("Las respuestas de error y las cabeceras del servicio vuelven intactas (el 422 no lo reescribe el gateway)")
    void passesServiceResponsesThrough() {
        CATALOG.stubFor(any(urlEqualTo("/api/v1/products/9f000000-0000-4000-8000-0000000000c1"))
                .willReturn(aResponse().withStatus(422)
                        .withHeader("Content-Type", "application/problem+json")
                        .withHeader("ETag", "\"4\"")
                        .withBody("{\"type\":\"urn:facturaflow:problem:immutable-field\",\"status\":422}")));

        EntityExchangeResult<String> result = client.put().uri("/api/v1/products/9f000000-0000-4000-8000-0000000000c1")
                .contentType(MediaType.APPLICATION_JSON).body("{}")
                .exchange().expectBody(String.class).returnResult();

        assertThat(result.getStatus().value()).isEqualTo(422);
        assertThat(result.getResponseHeaders().getContentType()).hasToString("application/problem+json");
        assertThat(result.getResponseHeaders().getETag()).isEqualTo("\"4\"");
        assertThat((String) JsonPath.read(result.getResponseBody(), "$.type"))
                .isEqualTo("urn:facturaflow:problem:immutable-field");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "/api/v1/unknown",
            "/api/v2/invoices",
            "/api/v1/invoicesX"
    })
    @DisplayName("Una ruta desconocida bajo /api responde 404 Problem Details del gateway y no llega a ningún servicio")
    void unknownRouteIsNotFound(String path) {
        EntityExchangeResult<String> result = client.get().uri(path)
                .exchange().expectBody(String.class).returnResult();

        assertThat(result.getStatus().value()).isEqualTo(404);
        assertThat(result.getResponseHeaders().getContentType()).hasToString("application/problem+json");
        assertThat((String) JsonPath.read(result.getResponseBody(), "$.type"))
                .isEqualTo("urn:facturaflow:problem:not-found");
        SERVICES.values().forEach(server -> assertThat(server.getAllServeEvents()).isEmpty());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"/swagger-ui.html", "/"})
    @DisplayName("Fuera de /api y del health no hay nada que pedir: se niega aunque el token sea de admin (ADR 0018)")
    void outsideApiIsDenied(String path) {
        EntityExchangeResult<String> result = client.get().uri(path)
                .exchange().expectBody(String.class).returnResult();

        assertThat(result.getStatus().value()).isEqualTo(403);
        assertThat((String) JsonPath.read(result.getResponseBody(), "$.type"))
                .isEqualTo("urn:facturaflow:problem:forbidden");
        SERVICES.values().forEach(server -> assertThat(server.getAllServeEvents()).isEmpty());
    }

    @Test
    @DisplayName("Un servicio que no responde da 504 Problem Details, sin trazas ni mensajes internos")
    void slowServiceIsGatewayTimeout() {
        SALES.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withFixedDelay(SLOW_RESPONSE_MILLIS)));

        EntityExchangeResult<String> result = client.get().uri("/api/v1/sales/5a000000-0000-4000-8000-000000000003")
                .exchange().expectBody(String.class).returnResult();

        assertThat(result.getStatus().value()).isEqualTo(504);
        assertThat((String) JsonPath.read(result.getResponseBody(), "$.type"))
                .isEqualTo("urn:facturaflow:problem:upstream-timeout");
        assertThat(result.getResponseBody()).doesNotContainIgnoringCase("exception").doesNotContain("localhost");
    }
}
