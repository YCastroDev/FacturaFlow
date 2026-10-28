package com.facturaflow.catalog.web;

import static com.facturaflow.catalog.model.CatalogFixtures.NOW;
import static com.facturaflow.catalog.model.CatalogFixtures.coffee;
import static com.facturaflow.catalog.model.CatalogFixtures.company;
import static com.facturaflow.catalog.model.CatalogFixtures.nit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

import com.facturaflow.catalog.FakeKeycloak;
import com.facturaflow.catalog.config.SecurityConfiguration;
import com.facturaflow.catalog.model.Customer;
import com.facturaflow.catalog.model.Product;
import com.facturaflow.catalog.service.CustomerService;
import com.facturaflow.catalog.service.ProductService;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Matriz de permisos de catalog (ADR 0018), fila por fila: sin token 401, con una identidad que no está en la fila
 * 403 y con una que sí, la respuesta del controlador (2xx). Incluye a {@code sales-service}, que no tiene roles de
 * persona sino el scope {@code catalog.read}: solo lecturas y la consulta por lote. Los tokens los valida el
 * decodificador REAL de Boot contra las claves de {@link FakeKeycloak}.
 */
@WebMvcTest({ProductController.class, CustomerController.class})
@Import(SecurityConfiguration.class)
class CatalogSecurityTest {

    private static final String ID = "9f000000-0000-4000-8000-0000000000c1";
    private static final String PRODUCT = """
            { "code": "CAFE-500", "name": "Café molido 500 g", "unitPrice": 18500.00, "unit": "UNIT",
              "taxes": [ { "type": "IVA", "rate": 5 } ] }""";
    private static final String CUSTOMER = """
            { "documentType": "NIT", "documentNumber": "890903938", "checkDigit": 8,
              "personType": "LEGAL_ENTITY", "companyName": "Cliente Ejemplo S.A.S.",
              "taxScheme": "IVA", "fiscalResponsibilities": [ "LARGE_TAXPAYER" ], "municipalityCode": "05001" }""";
    private static final String LOOKUP = """
            { "codes": [ "CAFE-500" ] }""";

    /** Identidades posibles: los tres roles del realm y el cliente de máquina de sales (solo catalog.read). */
    private static final List<String> IDENTITIES = List.of("admin", "vendedor", "contador", "sales-service");
    private static final Set<String> READERS = Set.of("admin", "vendedor", "sales-service");
    private static final Set<String> SELLERS = Set.of("admin", "vendedor");
    private static final Set<String> ADMIN = Set.of("admin");

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", FakeKeycloak::jwkSetUri);
    }

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private ProductService products;

    @MockitoBean
    private CustomerService customers;

    @BeforeEach
    void stubs() {
        Product product = new Product("CAFE-500", coffee(), NOW);
        Customer customer = new Customer(nit(), company(), NOW);
        // La versión la asigna JPA al guardar; el ETag la necesita (igual que en los tests de los controladores).
        ReflectionTestUtils.setField(product, "version", 0L);
        ReflectionTestUtils.setField(customer, "version", 0L);
        given(products.create(any(), any())).willReturn(product);
        given(products.get(any())).willReturn(product);
        given(products.getByCode(any())).willReturn(product);
        given(products.lookup(any())).willReturn(List.of(product));
        given(products.search(any(), any(), any())).willReturn(new PageImpl<>(List.of(product), PageRequest.of(0, 20), 1));
        given(products.update(any(), anyLong(), any(), any())).willReturn(product);
        given(products.activate(any(), any())).willReturn(product);
        given(products.deactivate(any(), any())).willReturn(product);
        given(customers.create(any(), any())).willReturn(customer);
        given(customers.get(any())).willReturn(customer);
        given(customers.getByDocument(any(), any())).willReturn(customer);
        given(customers.search(any(), any(), any()))
                .willReturn(new PageImpl<>(List.of(customer), PageRequest.of(0, 20), 1));
        given(customers.update(any(), anyLong(), any(), any())).willReturn(customer);
        given(customers.activate(any(), any())).willReturn(customer);
        given(customers.deactivate(any(), any())).willReturn(customer);
    }

    record Row(HttpMethod method, String uri, String body, Set<String> allowed) {

        @Override
        public String toString() {
            return method + " " + uri + " → " + allowed;
        }
    }

    static Stream<Row> matrix() {
        return Stream.of(
                // Productos
                new Row(HttpMethod.GET, "/api/v1/products", null, READERS),
                new Row(HttpMethod.GET, "/api/v1/products/" + ID, null, READERS),
                new Row(HttpMethod.GET, "/api/v1/products/by-code/CAFE-500", null, READERS),
                new Row(HttpMethod.POST, "/api/v1/products/lookup", LOOKUP, READERS),
                new Row(HttpMethod.POST, "/api/v1/products", PRODUCT, ADMIN),
                new Row(HttpMethod.PUT, "/api/v1/products/" + ID, PRODUCT, ADMIN),
                new Row(HttpMethod.POST, "/api/v1/products/" + ID + "/deactivate", null, ADMIN),
                new Row(HttpMethod.POST, "/api/v1/products/" + ID + "/activate", null, ADMIN),
                // Clientes
                new Row(HttpMethod.GET, "/api/v1/customers", null, READERS),
                new Row(HttpMethod.GET, "/api/v1/customers/" + ID, null, READERS),
                new Row(HttpMethod.GET, "/api/v1/customers/by-document/NIT/890903938", null, READERS),
                new Row(HttpMethod.POST, "/api/v1/customers", CUSTOMER, SELLERS),
                new Row(HttpMethod.PUT, "/api/v1/customers/" + ID, CUSTOMER, SELLERS),
                new Row(HttpMethod.POST, "/api/v1/customers/" + ID + "/deactivate", null, ADMIN),
                new Row(HttpMethod.POST, "/api/v1/customers/" + ID + "/activate", null, ADMIN));
    }

    static Stream<Arguments> matrixByIdentity() {
        return matrix().flatMap(row -> IDENTITIES.stream().map(identity -> Arguments.of(row, identity)));
    }

    private MvcTestResult call(Row row, String authorization) {
        MockMvcRequestBuilder request = mvc.method(row.method()).uri(row.uri());
        if (row.body() != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(row.body());
        }
        if (row.method() == HttpMethod.PUT) {
            request = request.header(HttpHeaders.IF_MATCH, "\"0\"");
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
    @DisplayName("con token: la identidad de la fila pasa (2xx); cualquier otra, 403 en Problem Details")
    void identityDecides(Row row, String identity) {
        MvcTestResult result = call(row, tokenOf(identity));

        if (row.allowed().contains(identity)) {
            assertThat(result.getResponse().getStatus()).as(row + " con " + identity).isBetween(200, 299);
        } else {
            assertProblem(result, HttpStatus.FORBIDDEN, "forbidden");
        }
    }

    static Stream<Arguments> otherServiceScopes() {
        return Stream.of(Arguments.of("sin scopes", ""), Arguments.of("con otro scope", "invoicing.read"));
    }

    @ParameterizedTest(name = "cliente de máquina {0}")
    @MethodSource("otherServiceScopes")
    @DisplayName("un cliente de máquina sin catalog.read no lee nada: el permiso es el scope, no el cliente")
    void serviceWithoutCatalogRead(String description, String scope) {
        assertProblem(mvc.get().uri("/api/v1/products").header(HttpHeaders.AUTHORIZATION,
                "Bearer " + FakeKeycloak.serviceToken(scope)).exchange(), HttpStatus.FORBIDDEN, "forbidden");
    }
}
