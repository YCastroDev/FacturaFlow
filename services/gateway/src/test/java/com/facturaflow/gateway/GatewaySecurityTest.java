package com.facturaflow.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * El filtro grueso del gateway (ADR 0018): sin token o con un token inválido, 401 sin llegar a ningún servicio; con
 * un rol de la aplicación, la petición pasa (la matriz fina la aplica cada servicio) con el token intacto; un cliente
 * de máquina (sin roles de persona) no entra por el gateway. Tokens de {@link FakeKeycloak}, validados por el
 * decodificador real de Boot.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestSecurityConfiguration.class)
class GatewaySecurityTest {

    private static final WireMockServer SERVICE = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        SERVICE.start();
    }

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void services(DynamicPropertyRegistry registry) {
        registry.add("GATEWAY_INVOICING_URI", SERVICE::baseUrl);
        registry.add("GATEWAY_CATALOG_URI", SERVICE::baseUrl);
        registry.add("GATEWAY_SALES_URI", SERVICE::baseUrl);
    }

    @AfterAll
    static void stop() {
        SERVICE.stop();
    }

    @BeforeEach
    void serviceAnswers200() {
        SERVICE.resetAll();
        SERVICE.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{}")));
    }

    /** Un cliente propio, sin el token por defecto de los demás tests. */
    private EntityExchangeResult<String> get(String path, String token) {
        RestTestClient client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        return client.get().uri(path)
                .headers(headers -> {
                    if (token != null) {
                        headers.setBearerAuth(token);
                    }
                })
                .exchange().expectBody(String.class).returnResult();
    }

    private static final List<String> ROUTES = List.of("/api/v1/invoices", "/api/v1/credit-notes/x",
            "/api/v1/products", "/api/v1/customers", "/api/v1/sales");

    static Stream<String> routes() {
        return ROUTES.stream();
    }

    static Stream<Arguments> routesByRole() {
        return ROUTES.stream().flatMap(route -> Stream.of("admin", "vendedor", "contador")
                .map(role -> Arguments.of(route, role)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("routes")
    @DisplayName("sin token: 401 Problem Details del gateway, sin llegar al servicio")
    void withoutTokenIs401(String route) {
        EntityExchangeResult<String> result = get(route, null);

        assertThat(result.getStatus().value()).isEqualTo(401);
        assertThat(result.getResponseHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
        assertThat((String) JsonPath.read(result.getResponseBody(), "$.type"))
                .isEqualTo("urn:facturaflow:problem:unauthorized");
        assertThat(SERVICE.getAllServeEvents()).isEmpty();
    }

    @ParameterizedTest(name = "{0} con {1}")
    @MethodSource("routesByRole")
    @DisplayName("con un rol de la aplicación pasa, y el servicio recibe el MISMO token (él aplica su matriz)")
    void anyApplicationRolePasses(String route, String role) {
        String token = FakeKeycloak.userToken(role);

        assertThat(get(route, token).getStatus().value()).isEqualTo(200);
        assertThat(SERVICE.getAllServeEvents()).singleElement()
                .satisfies(call -> assertThat(call.getRequest().getHeader("Authorization"))
                        .isEqualTo("Bearer " + token));
    }

    @Test
    @DisplayName("un token sin roles de la aplicación (sales-service, o un usuario sin rol) es 403 en el gateway")
    void tokensWithoutApplicationRoleAreForbidden() {
        EntityExchangeResult<String> service = get("/api/v1/products", FakeKeycloak.serviceToken("catalog.read"));
        EntityExchangeResult<String> noRole = get("/api/v1/products", FakeKeycloak.userToken("default-roles-facturaflow"));

        assertThat(service.getStatus().value()).isEqualTo(403);
        assertThat(noRole.getStatus().value()).isEqualTo(403);
        assertThat((String) JsonPath.read(service.getResponseBody(), "$.type"))
                .isEqualTo("urn:facturaflow:problem:forbidden");
        assertThat(SERVICE.getAllServeEvents()).isEmpty();
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
    @DisplayName("un token inválido es 401 invalid_token y no llega al servicio")
    void invalidTokenIs401(String reason, String token) {
        EntityExchangeResult<String> result = get("/api/v1/sales", token);

        assertThat(result.getStatus().value()).isEqualTo(401);
        assertThat(result.getResponseHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo("Bearer error=\"invalid_token\"");
        assertThat(SERVICE.getAllServeEvents()).isEmpty();
    }

    @Test
    @DisplayName("el health es público (healthcheck de compose y prueba de humo)")
    void healthIsPublic() {
        assertThat(get("/actuator/health", null).getStatus().value()).isEqualTo(200);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"vendedor", "contador"})
    @DisplayName("el resto de Actuator: sin token 401, sin admin 403, admin 200")
    void actuatorIsAdminOnly(String role) {
        assertThat(get("/actuator/metrics", null).getStatus().value()).isEqualTo(401);
        assertThat(get("/actuator/metrics", FakeKeycloak.userToken(role)).getStatus().value()).isEqualTo(403);
        assertThat(get("/actuator/metrics", FakeKeycloak.userToken("admin")).getStatus().value()).isEqualTo(200);
    }
}
