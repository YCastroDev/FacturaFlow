package com.facturaflow.catalog.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import tools.jackson.databind.json.JsonMapper;

/** Copia deliberada en los cuatro servicios (ADR 0018). */
class SecurityProblemResponsesTest {

    private final SecurityProblemResponses responses = new SecurityProblemResponses(JsonMapper.builder().build());
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/recurso");
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    @DisplayName("sin token: 401 con WWW-Authenticate: Bearer y el Problem Details de siempre")
    void missingToken() throws Exception {
        responses.commence(request, response, new InsufficientAuthenticationException("Full authentication"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(response.getContentAsString()).contains("\"type\":\"urn:facturaflow:problem:unauthorized\"",
                "\"status\":401", "\"instance\":\"/api/v1/recurso\"").doesNotContain("Full authentication");
    }

    @Test
    @DisplayName("token inválido: 401 invalid_token sin error_description ni el motivo de Spring")
    void invalidToken() throws Exception {
        responses.commence(request, response, new InvalidBearerTokenException("Jwt expired at 2026-10-28T10:00:00Z"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer error=\"invalid_token\"");
        assertThat(response.getContentAsString()).doesNotContain("expired", "2026");
    }

    @Test
    @DisplayName("sin el rol: 403 insufficient_scope")
    void forbidden() throws Exception {
        responses.handle(request, response, new AccessDeniedException("Access Denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer error=\"insufficient_scope\"");
        assertThat(response.getContentAsString()).contains("urn:facturaflow:problem:forbidden");
    }

    @Test
    @DisplayName("Keycloak no entrega las claves: 503 authentication-unavailable con Retry-After, no 401 ni 500")
    void keysUnavailable() throws Exception {
        responses.commence(request, response, new AuthenticationServiceException(
                "An error occurred while attempting to decode the Jwt: http://keycloak:8080/... Connection refused",
                new ConnectException("Connection refused")));

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("10");
        assertThat(response.getHeader("WWW-Authenticate")).isNull();
        assertThat(response.getContentAsString()).contains("urn:facturaflow:problem:authentication-unavailable")
                .doesNotContain("keycloak", "Connection refused");
    }
}
