package com.facturaflow.invoicing.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.invoicing.InfraImages;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * La migración V6 (ADR 0009) sobre datos existentes: aplica V1–V5, inserta una factura ISSUED como
 * la dejaba V5 y migra. El código único se conserva con su nombre neutro y la coherencia de estado
 * sigue vigente sobre la columna renombrada. Flyway directo, sin Spring.
 */
@Testcontainers
class V6MigrationTest {

    private static final String CUFE = "ab".repeat(48);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(InfraImages.POSTGRES);

    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private static JdbcClient jdbc() {
        return JdbcClient.create(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
    }

    @Test
    @DisplayName("una factura ISSUED conserva su CUFE en la columna de nombre neutro y siguen las reglas de estado")
    void renamesUniqueCodeKeepingData() {
        flyway("5").migrate();
        JdbcClient jdbc = jdbc();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO invoices (id, version, reference_code, status, issue_date, payment_method, payment_type,
                    customer_document_type, customer_document_number, customer_check_digit, customer_company_name,
                    customer_person_type, customer_tax_scheme, customer_fiscal_responsibilities, request_hash,
                    edoc_number, edoc_cufe, edoc_qr_url, edoc_public_url, edoc_validated_at, edoc_official_total)
                VALUES (?, 1, 'OLD-1', 'ISSUED', DATE '2026-10-20', 'CASH', 'CASH', 'NIT', '890903938', 8,
                    'Empresa S.A.S.', 'LEGAL_ENTITY', 'NOT_APPLICABLE', ARRAY['NOT_RESPONSIBLE'], ?,
                    'SETP1', ?, 'https://dian.example/qr', 'https://sandbox.example/1', now(), 1000.00)
                """)
                .params(id, "a".repeat(64), CUFE)
                .update();

        flyway("6").migrate();

        assertThat(jdbc.sql("SELECT edoc_unique_code FROM invoices WHERE id = ?").param(id)
                .query(String.class).single()).isEqualTo(CUFE);
        assertThatThrownBy(() -> jdbc.sql("UPDATE invoices SET edoc_unique_code = NULL WHERE id = ?").param(id)
                .update())
                .hasMessageContaining("ck_invoices_state_coherence");
        assertThat(jdbc.sql("SELECT count(*) FROM credit_notes").query(Long.class).single()).isZero();
    }
}
