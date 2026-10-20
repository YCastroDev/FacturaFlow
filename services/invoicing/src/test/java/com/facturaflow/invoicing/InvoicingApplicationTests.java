package com.facturaflow.invoicing;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Verifica que el contexto de Spring arranca contra un PostgreSQL real
 * (datasource, JPA y migraciones Flyway incluidas).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class InvoicingApplicationTests {

    @Test
    void contextLoads() {
    }
}
