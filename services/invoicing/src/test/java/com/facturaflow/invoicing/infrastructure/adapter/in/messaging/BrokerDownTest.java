package com.facturaflow.invoicing.infrastructure.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * RabbitMQ caído (ADR 0014): solo PostgreSQL real; el broker apunta a un puerto donde nadie escucha. Invoicing
 * arranca, su API REST atiende y su health sigue UP: el componente {@code messaging} es informativo y el indicador
 * {@code rabbit} de Spring Boot está desactivado. El listener se reconecta solo cuando el broker vuelve.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.rabbitmq.host=127.0.0.1", "spring.rabbitmq.port=1", "spring.rabbitmq.password=sin-broker"})
@AutoConfigureRestTestClient
@Import(BrokerDownTest.PostgresOnly.class)
class BrokerDownTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresOnly {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgresContainer() {
            return new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
        }
    }

    @Autowired
    private RestTestClient client;

    private EntityExchangeResult<String> fetch(String uri) {
        return client.get().uri(uri).exchange().expectBody(String.class).returnResult();
    }

    @Test
    @DisplayName("con RabbitMQ caído invoicing sigue UP y su API REST responde")
    void invoicingKeepsWorkingWithoutBroker() {
        assertThat((String) JsonPath.read(fetch("/actuator/health").getResponseBody(), "$.status")).isEqualTo("UP");
        assertThat((String) JsonPath.read(fetch("/actuator/health/liveness").getResponseBody(), "$.status")).isEqualTo("UP");
        assertThat((String) JsonPath.read(fetch("/actuator/health/readiness").getResponseBody(), "$.status")).isEqualTo("UP");
        assertThat(fetch("/actuator/health/rabbit").getStatus().value())
                .as("el indicador de Spring Boot está desactivado").isEqualTo(404);
        String dependencies = fetch("/actuator/health/dependencies").getResponseBody();
        assertThat((String) JsonPath.read(dependencies, "$.components.messaging.status")).isEqualTo("UP");
        assertThat((String) JsonPath.read(dependencies, "$.components.messaging.details.consumer.queue"))
                .isEqualTo("invoicing.sale-confirmed");
        assertThat((Integer) JsonPath.read(dependencies, "$.components.messaging.details.outbox.pendingEvents"))
                .as("la parte del productor (outbox de los eventos de facturación)").isZero();
        assertThat(fetch("/api/v1/invoices/" + UUID.randomUUID()).getStatus().value())
                .as("la API sigue atendiendo").isEqualTo(404);
    }
}
