package com.facturaflow.sales.infrastructure.adapter.in.rest;

import static com.facturaflow.sales.domain.model.SaleFixtures.line;
import static com.facturaflow.sales.domain.model.SaleFixtures.saleWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.facturaflow.sales.FakeKeycloak;
import com.facturaflow.sales.application.port.in.CreateSaleCommand;
import com.facturaflow.sales.application.port.in.CreateSaleResult;
import com.facturaflow.sales.application.port.in.CreateSaleUseCase;
import com.facturaflow.sales.application.port.in.GetSaleUseCase;
import com.facturaflow.sales.application.port.in.ListSalesUseCase;
import com.facturaflow.sales.application.port.out.SalePage;
import com.facturaflow.sales.domain.model.LineTax;
import com.facturaflow.sales.domain.model.Sale;
import com.facturaflow.sales.domain.model.TaxType;
import com.facturaflow.sales.infrastructure.config.SecurityConfiguration;
import com.nimbusds.jwt.JWTClaimsSet;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
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
 * Matriz de permisos de sales (ADR 0018), fila por fila: sin token 401, con un rol que no está en la fila 403 y con
 * uno que sí, la respuesta del controlador (2xx). Los tokens tienen la forma de los del realm y los valida el
 * decodificador REAL de Boot contra las claves de {@link FakeKeycloak} (firma, emisor, audiencia, vigencia), así
 * también se prueba el mapeo de {@code realm_access.roles} a autoridades.
 */
@WebMvcTest(SaleController.class)
@Import(SecurityConfiguration.class)
class SaleSecurityTest {

    private static final String BODY = """
            { "referenceCode": "VENTA-1", "customer": { "documentType": "NIT", "documentNumber": "890903938" },
              "lines": [ { "productCode": "CAFE-500", "quantity": 1 } ], "paymentMethod": "CASH",
              "paymentTerms": { "type": "CASH" } }""";

    /** Identidades posibles: los tres roles del realm y el cliente de máquina de sales (solo catalog.read). */
    private static final List<String> IDENTITIES = List.of("admin", "vendedor", "contador", "sales-service");

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", FakeKeycloak::jwkSetUri);
    }

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private CreateSaleUseCase createSale;

    @MockitoBean
    private GetSaleUseCase getSale;

    @MockitoBean
    private ListSalesUseCase listSales;

    private Sale sale;

    @BeforeEach
    void stubs() {
        sale = saleWith(List.of(line("1", "18500.00", "0", LineTax.of(TaxType.IVA, "19"))));
        given(createSale.create(any())).willReturn(CreateSaleResult.created(sale));
        given(getSale.byId(any())).willReturn(sale);
        given(getSale.byReferenceCode(any())).willReturn(sale);
        given(listSales.list(any())).willReturn(new SalePage(List.of(sale), 1, 20, 1, 1));
    }

    record Row(HttpMethod method, String uri, Set<String> allowed) {

        @Override
        public String toString() {
            return method + " " + uri + " → " + allowed;
        }
    }

    static Stream<Row> matrix() {
        return Stream.of(
                new Row(HttpMethod.POST, "/api/v1/sales", Set.of("admin", "vendedor")),
                new Row(HttpMethod.GET, "/api/v1/sales", Set.of("admin", "vendedor")),
                new Row(HttpMethod.GET, "/api/v1/sales/0c000000-0000-4000-8000-000000000001", Set.of("admin", "vendedor")),
                new Row(HttpMethod.GET, "/api/v1/sales/by-reference/VENTA-1", Set.of("admin", "vendedor")));
    }

    static Stream<Arguments> matrixByIdentity() {
        return matrix().flatMap(row -> IDENTITIES.stream().map(identity -> Arguments.of(row, identity)));
    }

    private MvcTestResult call(Row row, String authorization) {
        MockMvcRequestBuilder request = mvc.method(row.method()).uri(row.uri());
        if (row.method() == HttpMethod.POST) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(BODY);
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
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(status.value());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("matrix")
    @DisplayName("sin token: 401 en Problem Details con WWW-Authenticate: Bearer")
    void withoutTokenIs401(Row row) {
        MvcTestResult result = call(row, null);

        assertProblem(result, HttpStatus.UNAUTHORIZED, "unauthorized");
        assertThat(result).hasHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        assertThat(result).bodyJson().extractingPath("$.instance").isEqualTo(row.uri().split("\\?")[0]);
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

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"vendedor", "admin"})
    @DisplayName("quién hace la venta sale del token validado: su sub y su preferred_username llegan al comando")
    void soldByComesFromValidatedToken(String role) {
        String token = FakeKeycloak.userToken(claims -> claims.subject("4b9e0000-0000-4000-8000-0000000000c3")
                .claim("preferred_username", role + ".demo"), role);

        MvcTestResult result = mvc.post().uri("/api/v1/sales").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(BODY).exchange();

        assertThat(result).hasStatus(HttpStatus.CREATED);
        ArgumentCaptor<CreateSaleCommand> command = ArgumentCaptor.forClass(CreateSaleCommand.class);
        verify(createSale).create(command.capture());
        assertThat(command.getValue().soldByUserId()).isEqualTo("4b9e0000-0000-4000-8000-0000000000c3");
        assertThat(command.getValue().soldByUsername()).isEqualTo(role + ".demo");
    }

    @Test
    @DisplayName("una ruta fuera de la matriz se niega a todos, incluso al admin")
    void unknownRouteIsDenied() {
        assertProblem(mvc.delete().uri("/api/v1/sales/0c000000-0000-4000-8000-000000000001")
                .header(HttpHeaders.AUTHORIZATION, tokenOf("admin")).exchange(), HttpStatus.FORBIDDEN, "forbidden");
    }

    static Stream<Arguments> invalidTokens() {
        Instant past = Instant.now().minusSeconds(600);
        UnaryOperator<JWTClaimsSet.Builder> expired = c -> c.issueTime(Date.from(past.minusSeconds(300)))
                .expirationTime(Date.from(past));
        return Stream.of(
                Arguments.of("vencido", FakeKeycloak.userToken(expired, "admin")),
                Arguments.of("de otro emisor", FakeKeycloak.userToken(
                        c -> c.issuer("http://localhost:8180/realms/otro"), "admin")),
                Arguments.of("sin la audiencia facturaflow-api", FakeKeycloak.userToken(
                        c -> c.audience("account"), "admin")),
                Arguments.of("firmado con otra clave", FakeKeycloak.tokenSignedWithUnknownKey("admin")),
                Arguments.of("mal formado", "no-es-un-jwt"));
    }

    @ParameterizedTest(name = "token {0}")
    @MethodSource("invalidTokens")
    @DisplayName("un token inválido es 401 invalid_token, sin decir por qué (ni en la cabecera ni en el cuerpo)")
    void invalidTokenIs401(String reason, String token) {
        MvcTestResult result = mvc.get().uri("/api/v1/sales").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();

        assertProblem(result, HttpStatus.UNAUTHORIZED, "unauthorized");
        assertThat(result).hasHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");
        assertThat(result).bodyText().doesNotContainIgnoringCase("expired").doesNotContainIgnoringCase("issuer")
                .doesNotContainIgnoringCase("aud").doesNotContain("Jwt").doesNotContain(token);
    }
}
