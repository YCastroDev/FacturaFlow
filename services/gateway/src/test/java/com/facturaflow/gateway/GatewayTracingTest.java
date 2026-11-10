package com.facturaflow.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Propagación W3C por el gateway (ADR 0019). El proxy usa el {@code RestClient.Builder} instrumentado de Spring Boot:
 * el servicio recibe un {@code traceparent} de la MISMA traza pero con el span cliente del gateway (no el del llamador,
 * que solo se copiaría si el gateway reenviara la cabecera sin participar en la traza).
 *
 * <p>{@code @AutoConfigureTracing}: sin ella, Spring Boot deja el tracing inactivo en los tests (no se crean spans ni
 * se propaga nada; el colector no hace falta: sin endpoint OTLP no hay exportador).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@AutoConfigureTracing
@Import(TestSecurityConfiguration.class)
class GatewayTracingTest {

    private static final WireMockServer SALES = new WireMockServer(wireMockConfig().dynamicPort());
    private static final String W3C = "00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}";

    static {
        SALES.start();
    }

    @Autowired
    private RestTestClient client;

    @DynamicPropertySource
    static void services(DynamicPropertyRegistry registry) {
        registry.add("GATEWAY_SALES_URI", SALES::baseUrl);
    }

    @AfterAll
    static void stop() {
        SALES.stop();
    }

    @BeforeEach
    void stub() {
        SALES.resetAll();
        SALES.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{}")));
    }

    private String forwardedTraceParent() {
        List<LoggedRequest> requests = SALES.findAll(anyRequestedFor(anyUrl()));
        assertThat(requests).hasSize(1);
        return requests.getFirst().getHeader("traceparent");
    }

    @Test
    @DisplayName("el servicio recibe la traza del cliente, con el span del gateway como padre")
    void continuesTheCallersTrace() {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String callerSpan = "00f067aa0ba902b7";

        client.get().uri("/api/v1/sales/5a000000-0000-4000-8000-000000000003")
                .header("traceparent", "00-" + traceId + "-" + callerSpan + "-01")
                .exchange().expectStatus().isOk();

        String forwarded = forwardedTraceParent();
        assertThat(forwarded).matches(W3C);
        assertThat(forwarded.split("-")[1]).as("la misma traza").isEqualTo(traceId);
        assertThat(forwarded.split("-")[2]).as("el span cliente del gateway").isNotEqualTo(callerSpan);
    }

    @Test
    @DisplayName("sin traceparent, el gateway empieza la traza y la propaga al servicio")
    void startsATraceWhenTheCallerHasNone() {
        client.get().uri("/api/v1/sales/5a000000-0000-4000-8000-000000000003").exchange().expectStatus().isOk();

        assertThat(forwardedTraceParent()).matches(W3C);
    }
}
