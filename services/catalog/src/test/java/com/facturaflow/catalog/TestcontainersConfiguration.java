package com.facturaflow.catalog;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Levanta un PostgreSQL desechable para los tests. {@link ServiceConnection} hace que Spring use la URL
 * y credenciales del contenedor en lugar de las variables de entorno de application.yml.
 *
 * <p>Importa {@link TestSecurityConfiguration}: todo test con la aplicación completa valida tokens (ADR 0018).
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(TestSecurityConfiguration.class)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(InfraImages.POSTGRES);
    }
}
