package com.facturaflow.catalog.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.facturaflow.catalog.FakeKeycloak;
import com.facturaflow.catalog.config.SecurityConfiguration;
import com.facturaflow.catalog.service.ProductService;
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
@WebMvcTest(ProductController.class)
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
    private ProductService products;

    @Test
    @DisplayName("con token y Keycloak caído: 503 authentication-unavailable con Retry-After")
    void keysUnavailableIs503() {
        MvcTestResult result = mvc.get().uri("/api/v1/products")
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
        assertThat(mvc.get().uri("/api/v1/products").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
    }
}
