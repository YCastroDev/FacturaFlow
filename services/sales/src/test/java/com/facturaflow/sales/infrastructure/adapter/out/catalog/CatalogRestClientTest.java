package com.facturaflow.sales.infrastructure.adapter.out.catalog;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.sales.application.exception.CatalogUnavailableException;
import com.facturaflow.sales.application.exception.CatalogUnavailableException.Reason;
import com.facturaflow.sales.application.port.out.CatalogCustomer;
import com.facturaflow.sales.application.port.out.CatalogProduct;
import com.facturaflow.sales.domain.model.CustomerDocumentRef;
import com.facturaflow.sales.domain.model.DocumentType;
import com.facturaflow.sales.domain.model.FiscalResponsibility;
import com.facturaflow.sales.domain.model.LineTax;
import com.facturaflow.sales.domain.model.Money;
import com.facturaflow.sales.domain.model.TaxType;
import com.facturaflow.sales.domain.model.UnitOfMeasure;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * El adaptador de catalog contra un catalog falso (WireMock) que responde como el real (ADR 0011).
 * Valores de resiliencia de producción, salvo el timeout de lectura: holgado en los tests que no tratan de
 * tiempos (en un runner lento, un timeout justo convierte un 503 en un timeout) y corto, frente a una
 * respuesta mucho más lenta, solo en el que prueba el timeout.
 */
class CatalogRestClientTest {

    private static final String NIT_PATH = "/api/v1/customers/by-document/NIT/890903938";
    private static final String LOOKUP_PATH = "/api/v1/products/lookup";
    private static final CustomerDocumentRef NIT = new CustomerDocumentRef(DocumentType.NIT, "890903938");
    private static final Duration GENEROUS_READ_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration SHORT_READ_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration SLOW_RESPONSE = Duration.ofSeconds(5);

    static final String CUSTOMER_JSON = """
            { "id": "c0000000-0000-4000-8000-000000000001", "documentType": "NIT", "documentNumber": "890903938",
              "checkDigit": 8, "personType": "LEGAL_ENTITY", "companyName": "Cliente Ejemplo S.A.S.", "names": null,
              "displayName": "Cliente Ejemplo S.A.S.", "taxScheme": "IVA",
              "fiscalResponsibilities": [ "LARGE_TAXPAYER", "SELF_WITHHOLDER" ], "municipalityCode": "05001",
              "address": "Carrera 48 # 10-45", "phone": "+576044445555", "email": "facturacion@cliente.example",
              "active": %s, "version": 3, "createdAt": "2026-10-23T15:00:00Z", "campoNuevo": "se ignora" }""";

    static final String LOOKUP_JSON = """
            { "items": [
              { "id": "9f000000-0000-4000-8000-0000000000a1", "code": "ARROZ-KG", "name": "Arroz", "unitPrice": 4200.00,
                "unit": "KILOGRAM", "ivaExcluded": true, "taxes": [], "active": false, "version": 1 },
              { "id": "9f000000-0000-4000-8000-0000000000c1", "code": "CAFE-500", "name": "Café molido 500 g",
                "unitPrice": 18500.00, "unit": "UNIT", "ivaExcluded": false,
                "taxes": [ { "type": "IVA", "rate": 19.00 }, { "type": "INC", "rate": 8.00 } ], "active": true } ] }""";

    private WireMockServer catalog;
    private CatalogResilience resilience;
    private CatalogRestClient client;

    static CatalogProperties properties(URI baseUrl, Duration readTimeout) {
        return new CatalogProperties(baseUrl, Duration.ofSeconds(1), readTimeout, Duration.ofSeconds(5),
                new CatalogProperties.Resilience(
                        new CatalogProperties.RetrySettings(2, Duration.ofMillis(50), 0.5),
                        new CatalogProperties.CircuitBreakerSettings(10, 5, 50, Duration.ofSeconds(30), 2)));
    }

    private CatalogRestClient clientFor(URI baseUrl) {
        return clientFor(baseUrl, GENEROUS_READ_TIMEOUT);
    }

    private CatalogRestClient clientFor(URI baseUrl, Duration readTimeout) {
        CatalogProperties properties = properties(baseUrl, readTimeout);
        resilience = new CatalogResilience(properties, CircuitBreakerRegistry.ofDefaults(), RetryRegistry.ofDefaults());
        return new CatalogRestClient(new CatalogHttp(properties, resilience));
    }

    @BeforeEach
    void start() {
        catalog = new WireMockServer(wireMockConfig().dynamicPort());
        catalog.start();
        client = clientFor(URI.create(catalog.baseUrl()));
    }

    @AfterEach
    void stop() {
        catalog.stop();
    }

    private static Set<String> codes(String... codes) {
        return new LinkedHashSet<>(List.of(codes));
    }

    @Test
    @DisplayName("cliente encontrado: su copia completa, tolerando campos nuevos de catalog")
    void customerFound() {
        catalog.stubFor(get(NIT_PATH).willReturn(okJson(CUSTOMER_JSON.formatted("true"))));

        CatalogCustomer customer = client.findCustomer(NIT).orElseThrow();

        assertThat(customer.active()).isTrue();
        assertThat(customer.snapshot().checkDigit()).isEqualTo(8);
        assertThat(customer.snapshot().displayName()).isEqualTo("Cliente Ejemplo S.A.S.");
        assertThat(customer.snapshot().fiscalResponsibilities())
                .containsExactlyInAnyOrder(FiscalResponsibility.LARGE_TAXPAYER, FiscalResponsibility.SELF_WITHHOLDER);
        assertThat(customer.snapshot().municipalityCode()).isEqualTo("05001");
    }

    @Test
    @DisplayName("cliente inactivo: llega con active=false (decide el caso de uso)")
    void customerInactive() {
        catalog.stubFor(get(NIT_PATH).willReturn(okJson(CUSTOMER_JSON.formatted("false"))));

        assertThat(client.findCustomer(NIT)).hasValueSatisfying(customer -> assertThat(customer.active()).isFalse());
    }

    @Test
    @DisplayName("cliente inexistente (404): vacío, sin reintentos y sin contar como falla del circuito")
    void customerNotFound() {
        catalog.stubFor(get(NIT_PATH).willReturn(aResponse().withStatus(404)
                .withHeader("Content-Type", "application/problem+json").withBody("{\"status\":404}")));

        Optional<CatalogCustomer> customer = client.findCustomer(NIT);

        assertThat(customer).isEmpty();
        catalog.verify(1, getRequestedFor(urlEqualTo(NIT_PATH)));
        assertThat(resilience.circuitBreaker().getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    @DisplayName("productos: UNA petición por lote con todos los códigos; activos e inactivos con sus impuestos")
    void productLookup() {
        catalog.stubFor(post(LOOKUP_PATH).withRequestBody(equalToJson("{ \"codes\": [ \"CAFE-500\", \"arroz-kg\" ] }"))
                .willReturn(okJson(LOOKUP_JSON)));

        List<CatalogProduct> products = client.findProducts(codes("CAFE-500", "arroz-kg"));

        catalog.verify(1, postRequestedFor(urlEqualTo(LOOKUP_PATH)));
        assertThat(products).extracting(CatalogProduct::code).containsExactly("ARROZ-KG", "CAFE-500");
        assertThat(products.getFirst().active()).isFalse();
        assertThat(products.getFirst().unit()).isEqualTo(UnitOfMeasure.KILOGRAM);
        CatalogProduct coffee = products.get(1);
        assertThat(coffee.unitPrice()).isEqualTo(Money.of("18500"));
        assertThat(coffee.taxes()).containsExactly(LineTax.of(TaxType.IVA, "19"), LineTax.of(TaxType.INC, "8"));
    }

    @Test
    @DisplayName("503 pasajero: se reintenta una vez y la segunda respuesta sirve")
    void transient503IsRetried() {
        catalog.stubFor(get(NIT_PATH).inScenario("reinicio").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("arriba"));
        catalog.stubFor(get(NIT_PATH).inScenario("reinicio").whenScenarioStateIs("arriba")
                .willReturn(okJson(CUSTOMER_JSON.formatted("true"))));

        assertThat(client.findCustomer(NIT)).isPresent();
        catalog.verify(2, getRequestedFor(urlEqualTo(NIT_PATH)));
    }

    @Test
    @DisplayName("503 persistente: 2 intentos y 503 para el cliente con Retry-After de 5 s")
    void persistent503() {
        catalog.stubFor(get(NIT_PATH).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> client.findCustomer(NIT))
                .isInstanceOfSatisfying(CatalogUnavailableException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.FAILURE);
                    assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(e.getMessage()).contains("HTTP 503");
                });
        catalog.verify(2, getRequestedFor(urlEqualTo(NIT_PATH)));
    }

    @Test
    @DisplayName("500: no es pasajero, no se reintenta, pero cuenta como falla")
    void http500IsNotRetried() {
        catalog.stubFor(post(LOOKUP_PATH).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client.findProducts(codes("CAFE-500"))).isInstanceOf(CatalogUnavailableException.class);
        catalog.verify(1, postRequestedFor(urlEqualTo(LOOKUP_PATH)));
        assertThat(resilience.circuitBreaker().getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("timeout de lectura: no se reintenta (ya gastó el tiempo) y es 503")
    void readTimeoutIsNotRetried() {
        CatalogRestClient impatient = clientFor(URI.create(catalog.baseUrl()), SHORT_READ_TIMEOUT);
        catalog.stubFor(get(NIT_PATH).willReturn(okJson(CUSTOMER_JSON.formatted("true"))
                .withFixedDelay((int) SLOW_RESPONSE.toMillis())));

        long started = System.nanoTime();
        assertThatThrownBy(() -> impatient.findCustomer(NIT)).isInstanceOf(CatalogUnavailableException.class)
                .hasMessageContaining("HttpTimeoutException");

        // No esperó la respuesta lenta (el "sin reintento" lo prueba verify).
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(SLOW_RESPONSE.minusSeconds(1));
        catalog.verify(1, getRequestedFor(urlEqualTo(NIT_PATH)));
    }

    @Test
    @DisplayName("sin conexión (puerto cerrado): falla rápido, marcada como 'sin conexión' (reintentable)")
    void connectionRefused() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        CatalogRestClient refused = clientFor(URI.create("http://localhost:" + closedPort));

        assertThatThrownBy(() -> refused.findCustomer(NIT))
                .isInstanceOf(CatalogUnavailableException.class).hasMessageContaining("sin conexión");
        assertThat(resilience.circuitBreaker().getMetrics().getNumberOfFailedCalls()).as("2 intentos").isEqualTo(2);
    }

    @Test
    @DisplayName("tras fallas repetidas el circuito se abre: 503 inmediato, sin llamar a catalog, con el tiempo que falta")
    void circuitOpens() {
        catalog.stubFor(post(LOOKUP_PATH).willReturn(aResponse().withStatus(500)));
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> client.findProducts(codes("CAFE-500"))).isInstanceOf(CatalogUnavailableException.class);
        }
        assertThat(resilience.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        catalog.resetRequests();

        assertThatThrownBy(() -> client.findCustomer(NIT))
                .isInstanceOfSatisfying(CatalogUnavailableException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.CIRCUIT_OPEN);
                    assertThat(e.retryAfter()).isBetween(Duration.ofSeconds(1), Duration.ofSeconds(30));
                });
        assertThat(catalog.getAllServeEvents()).isEmpty();
        assertThat(resilience.snapshot()).containsEntry("circuitBreaker", "OPEN").containsKey("retryAfterSeconds");
    }

    @ParameterizedTest(name = "faltan {0} ms → Retry-After de {1} s")
    @CsvSource({"400, 1", "1000, 1", "1001, 2", "29999, 30", "0, 1", "-250, 1"})
    @DisplayName("el Retry-After del circuito abierto son segundos enteros hacia arriba, nunca menos de 1 s")
    void retryAfterRoundsUp(long remainingMillis, long expectedSeconds) {
        assertThat(CatalogResilience.wholeSecondsRoundedUp(Duration.ofMillis(remainingMillis)))
                .isEqualTo(Duration.ofSeconds(expectedSeconds));
    }

    @Test
    @DisplayName("una respuesta inesperada (400) es un error del contrato: 500, sin contar en el circuito")
    void unexpectedStatus() {
        catalog.stubFor(get(NIT_PATH).willReturn(aResponse().withStatus(400)));

        assertThatThrownBy(() -> client.findCustomer(NIT)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 400");
        assertThat(resilience.circuitBreaker().getMetrics().getNumberOfFailedCalls()).isZero();
    }
}
