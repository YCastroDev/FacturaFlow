package com.facturaflow.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Los servicios caídos: invoicing apunta a un puerto donde no escucha nadie y catalog y sales a sus nombres de docker
 * compose, que aquí no existen. El gateway responde 502 y su health no depende de ellos (ADR 0017).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
class GatewayServiceDownTest {

    @Autowired
    private RestTestClient client;

    @DynamicPropertySource
    static void unreachableInvoicing(DynamicPropertyRegistry registry) {
        registry.add("GATEWAY_INVOICING_URI", () -> "http://localhost:" + freePort());
    }

    @Test
    @DisplayName("Un servicio caído da 502 Problem Details y el health del gateway sigue UP")
    void unreachableServiceIsBadGateway() {
        EntityExchangeResult<String> result = client.get().uri("/api/v1/invoices/7c3f2a10-0000-4000-8000-000000000001")
                .exchange().expectBody(String.class).returnResult();

        assertThat(result.getStatus().value()).isEqualTo(502);
        assertThat(result.getResponseHeaders().getContentType()).hasToString("application/problem+json");
        assertThat((String) JsonPath.read(result.getResponseBody(), "$.type"))
                .isEqualTo("urn:facturaflow:problem:upstream-unavailable");
        assertThat(result.getResponseBody()).doesNotContainIgnoringCase("exception").doesNotContain("localhost");

        client.get().uri("/actuator/health").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
