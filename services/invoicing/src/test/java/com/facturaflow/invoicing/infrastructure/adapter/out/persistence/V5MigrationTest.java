package com.facturaflow.invoicing.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * La migración V5 sobre datos existentes (ADR 0008): aplica V1–V4, inserta filas como las que
 * dejaba V4 y migra a V5. Las facturas sin documento migran; una ISSUED hace abortar la migración
 * sin aplicar nada. Flyway directo, sin Spring; cada test usa su propio esquema.
 */
@Testcontainers
class V5MigrationTest {

    private static final String CUFE = "a".repeat(96);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    private static Flyway flyway(String schema, String target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private static JdbcClient jdbc(String schema) {
        return JdbcClient.create(new DriverManagerDataSource(POSTGRES.getJdbcUrl() + "&currentSchema=" + schema,
                POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    /** Una factura PENDING tal como la dejaba V4. */
    private static UUID insertPendingV4Invoice(JdbcClient jdbc, String reference) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO invoices (id, version, reference_code, status, issue_date, payment_method, payment_type,
                    customer_document_type, customer_document_number, customer_check_digit, customer_company_name,
                    customer_person_type, customer_tax_scheme, customer_fiscal_responsibilities, request_hash)
                VALUES (?, 0, ?, 'PENDING', DATE '2026-10-20', 'CASH', 'CASH', 'NIT', '890903938', 8,
                    'Empresa S.A.S.', 'LEGAL_ENTITY', 'NOT_APPLICABLE', ARRAY['NOT_RESPONSIBLE'], ?)
                """)
                .params(id, reference, "a".repeat(64))
                .update();
        return id;
    }

    private static void issueWithV4Columns(JdbcClient jdbc, UUID id) {
        jdbc.sql("""
                UPDATE invoices SET status = 'ISSUED', edoc_number = 'FAKE-1', edoc_cufe = ?,
                    edoc_pdf_url = 'https://fake-provider.invalid/invoices/FAKE-1.pdf',
                    edoc_validated_at = now(), edoc_official_total = 1190 WHERE id = ?""")
                .params(CUFE, id)
                .update();
    }

    private static String currentVersion(JdbcClient jdbc) {
        return jdbc.sql("SELECT max(version) FROM flyway_schema_history WHERE success")
                .query(String.class).single();
    }

    private static List<String> columns(JdbcClient jdbc) {
        return jdbc.sql("SELECT column_name FROM information_schema.columns WHERE table_name = 'invoices' "
                + "AND table_schema = current_schema()").query(String.class).list();
    }

    @Test
    @DisplayName("las facturas sin documento electrónico migran y quedan con las columnas de los dos enlaces")
    void migratesInvoicesWithoutDocument() {
        flyway("pending", "4").migrate();
        JdbcClient jdbc = jdbc("pending");
        insertPendingV4Invoice(jdbc, "OLD-1");

        flyway("pending", "5").migrate();

        assertThat(currentVersion(jdbc)).isEqualTo("5");
        assertThat(columns(jdbc)).contains("edoc_qr_url", "edoc_public_url").doesNotContain("edoc_pdf_url");
        assertThat(jdbc.sql("SELECT count(*) FROM invoices WHERE status = 'PENDING'").query(Integer.class).single())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("si hay una factura ISSUED, la migración aborta con un mensaje claro y no aplica nada")
    void abortsWhenIssuedInvoicesExist() {
        flyway("issued", "4").migrate();
        JdbcClient jdbc = jdbc("issued");
        issueWithV4Columns(jdbc, insertPendingV4Invoice(jdbc, "OLD-1"));

        assertThatThrownBy(() -> flyway("issued", "5").migrate())
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("hay facturas ISSUED");
        assertThat(currentVersion(jdbc)).isEqualTo("4");
        assertThat(columns(jdbc)).contains("edoc_pdf_url").doesNotContain("edoc_qr_url");
    }

    @Test
    @DisplayName("después de V5, una factura ISSUED sin la URL de consulta de la DIAN viola la coherencia de estado")
    void issuedRequiresBothLinks() {
        flyway("check", "5").migrate();
        JdbcClient jdbc = jdbc("check");
        UUID id = insertPendingV4Invoice(jdbc, "NEW-1");

        assertThatThrownBy(() -> jdbc.sql("""
                UPDATE invoices SET status = 'ISSUED', edoc_number = 'SETP1', edoc_cufe = ?,
                    edoc_public_url = 'https://sandbox.example/documents/1',
                    edoc_validated_at = now(), edoc_official_total = 1190 WHERE id = ?""")
                .params(CUFE, id)
                .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_invoices_state_coherence");
    }
}
