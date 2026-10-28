package com.facturaflow.sales.infrastructure.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.facturaflow.sales.FakeKeycloak;
import com.facturaflow.sales.application.port.in.CreateSaleUseCase;
import com.facturaflow.sales.application.port.in.GetSaleUseCase;
import com.facturaflow.sales.application.port.in.ListSalesUseCase;
import com.facturaflow.sales.infrastructure.config.SecurityConfiguration;
import java.io.IOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Keycloak caído y sin claves en caché (ADR 0018): el token no se puede validar, así que la respuesta es 503
 * {@code authentication-unavailable} con {@code Retry-After}, ni 401 (el token puede ser bueno) ni el 500 que
 * daría Spring por defecto. Sin token sigue siendo 401: no hace falta Keycloak para saberlo.
 */
@WebMvcTest(SaleController.class)
@Import(SecurityConfiguration.class)
class KeycloakDownSecurityTest {

    @DynamicPropertySource
    static void unreachableKeys(DynamicPropertyRegistry registry) throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://localhost:" + closedPort + FakeKeycloak.CERTS_PATH);
    }

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private CreateSaleUseCase createSale;

    @MockitoBean
    private GetSaleUseCase getSale;

    @MockitoBean
    private ListSalesUseCase listSales;

    @Test
    @DisplayName("con token y Keycloak caído: 503 authentication-unavailable con Retry-After")
    void keysUnavailableIs503() {
        MvcTestResult result = mvc.get().uri("/api/v1/sales")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + FakeKeycloak.userToken("vendedor")).exchange();

        assertThat(result).hasStatus(HttpStatus.SERVICE_UNAVAILABLE).hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .hasHeader(HttpHeaders.RETRY_AFTER, "10");
        assertThat(result).bodyJson().extractingPath("$.type")
                .isEqualTo("urn:facturaflow:problem:authentication-unavailable");
        assertThat(result).bodyText().doesNotContain("localhost", "Connection");
    }

    @Test
    @DisplayName("sin token y Keycloak caído: 401 (no hace falta Keycloak para rechazarlo)")
    void withoutTokenStill401() {
        assertThat(mvc.get().uri("/api/v1/sales").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
    }
}
