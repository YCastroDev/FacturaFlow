package com.facturaflow.sales;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Levanta un PostgreSQL y un RabbitMQ desechables para los tests. {@link ServiceConnection} hace que
 * Spring use la URL y credenciales de los contenedores en lugar de las variables de entorno de
 * application.yml. RabbitMQ usa la misma imagen que docker compose (ADR 0013).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    public static final DockerImageName RABBITMQ_IMAGE = DockerImageName.parse("rabbitmq:4.3.6-management-alpine");

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
    }

    @Bean
    @ServiceConnection
    RabbitMQContainer rabbitContainer() {
        return new RabbitMQContainer(RABBITMQ_IMAGE);
    }
}
