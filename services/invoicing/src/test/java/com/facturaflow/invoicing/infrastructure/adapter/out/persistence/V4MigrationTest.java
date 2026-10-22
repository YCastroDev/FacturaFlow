package com.facturaflow.invoicing.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * La migración V4 sobre datos existentes (ADR 0007): aplica V1–V3, inserta filas como las que
 * dejaba V3 y migra a V4. Verifica el relleno determinista y que aborta (sin aplicar nada) ante
 * datos ambiguos. Flyway directo, sin Spring; cada test usa su propio esquema.
 */
@Testcontainers
class V4MigrationTest {

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

    /** Una fila tal como la dejaba V3. */
    private static UUID insertV3Invoice(JdbcClient jdbc, String reference, String documentType, Integer checkDigit,
                                        String companyName, String names, String unit) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO invoices (id, version, reference_code, status, issue_date, payment_method, payment_type,
                    customer_document_type, customer_document_number, customer_check_digit, customer_company_name,
                    customer_names, request_hash)
                VALUES (?, 0, ?, 'PENDING', DATE '2026-10-20', 'CASH', 'CASH', ?, '890903938', ?, ?, ?, ?)
                """)
                .params(id, reference, documentType, checkDigit, companyName, names, "a".repeat(64))
                .update();
        jdbc.sql("""
                INSERT INTO invoice_lines (id, invoice_id, line_number, code, name, quantity, unit_price,
                    discount_rate, unit_of_measure, iva_excluded)
                VALUES (?, ?, 0, 'SKU-1', 'Producto', 1, 1000, 0, ?, false)
                """)
                .params(UUID.randomUUID(), id, unit)
                .update();
        return id;
    }

    private static String currentVersion(JdbcClient jdbc) {
        return jdbc.sql("SELECT max(version) FROM flyway_schema_history WHERE success")
                .query(String.class).single();
    }

    @Test
    @DisplayName("las filas existentes se completan de forma determinista según tengan razón social o nombres")
    void backfillsExistingRows() {
        flyway("backfill", "3").migrate();
        JdbcClient jdbc = jdbc("backfill");
        UUID company = insertV3Invoice(jdbc, "OLD-1", "NIT", 8, "Empresa S.A.S.", null, "UNIT");
        UUID person = insertV3Invoice(jdbc, "OLD-2", "CITIZENSHIP_CARD", null, null, "Ana Gómez", "KILOGRAM");

        flyway("backfill", "4").migrate();

        Map<String, Object> companyRow = jdbc.sql("""
                SELECT customer_person_type, customer_tax_scheme,
                       array_to_string(customer_fiscal_responsibilities, ',') AS responsibilities,
                       customer_municipality_code, customer_address, customer_phone
                FROM invoices WHERE id = ?""").param(company).query().singleRow();
        assertThat(companyRow).containsEntry("customer_person_type", "LEGAL_ENTITY")
                .containsEntry("customer_tax_scheme", "NOT_APPLICABLE")
                .containsEntry("responsibilities", "NOT_RESPONSIBLE")
                .containsEntry("customer_municipality_code", null)
                .containsEntry("customer_address", null)
                .containsEntry("customer_phone", null);
        assertThat(jdbc.sql("SELECT customer_person_type FROM invoices WHERE id = ?").param(person)
                .query(String.class).single()).isEqualTo("NATURAL_PERSON");
        assertThat(currentVersion(jdbc)).isEqualTo("4");
    }

    @Test
    @DisplayName("una base vacía migra sin problemas")
    void migratesEmptyDatabase() {
        flyway("empty", "4").migrate();

        assertThat(currentVersion(jdbc("empty"))).isEqualTo("4");
    }

    @Test
    @DisplayName("si un cliente tiene razón social y nombres a la vez, la migración aborta y no aplica nada")
    void abortsOnAmbiguousPersonType() {
        flyway("ambiguous", "3").migrate();
        JdbcClient jdbc = jdbc("ambiguous");
        insertV3Invoice(jdbc, "OLD-1", "NIT", 8, "Empresa S.A.S.", "Ana Gómez", "UNIT");

        assertThatThrownBy(() -> flyway("ambiguous", "4").migrate())
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("razón social Y nombres");
        assertThat(currentVersion(jdbc)).isEqualTo("3");
        assertThat(columns(jdbc)).doesNotContain("customer_person_type");
    }

    @Test
    @DisplayName("si una línea usa una unidad eliminada del dominio, la migración aborta y no aplica nada")
    void abortsOnRemovedUnit() {
        flyway("removed_unit", "3").migrate();
        JdbcClient jdbc = jdbc("removed_unit");
        insertV3Invoice(jdbc, "OLD-1", "NIT", 8, "Empresa S.A.S.", null, "HOUR");

        assertThatThrownBy(() -> flyway("removed_unit", "4").migrate())
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("unidades que el dominio ya no admite");
        assertThat(currentVersion(jdbc)).isEqualTo("3");
        assertThat(jdbc.sql("SELECT unit_of_measure FROM invoice_lines").query(String.class).single())
                .isEqualTo("HOUR");
    }

    private static List<String> columns(JdbcClient jdbc) {
        return jdbc.sql("SELECT column_name FROM information_schema.columns WHERE table_name = 'invoices' "
                + "AND table_schema = current_schema()").query(String.class).list();
    }
}
