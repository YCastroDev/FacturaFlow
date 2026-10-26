package com.facturaflow.sales.infrastructure.adapter.out.persistence;

import static com.facturaflow.sales.domain.model.SaleFixtures.customer;
import static com.facturaflow.sales.domain.model.SaleFixtures.excludedLine;
import static com.facturaflow.sales.domain.model.SaleFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.sales.TestcontainersConfiguration;
import com.facturaflow.sales.application.exception.DuplicateReferenceCodeException;
import com.facturaflow.sales.application.port.out.RequestHash;
import com.facturaflow.sales.application.port.out.SalePage;
import com.facturaflow.sales.application.port.out.StoredSale;
import com.facturaflow.sales.domain.model.LineTax;
import com.facturaflow.sales.domain.model.Money;
import com.facturaflow.sales.domain.model.PaymentMethod;
import com.facturaflow.sales.domain.model.PaymentTerms;
import com.facturaflow.sales.domain.model.ReferenceCode;
import com.facturaflow.sales.domain.model.Sale;
import com.facturaflow.sales.domain.model.SaleId;
import com.facturaflow.sales.domain.model.SaleStatus;
import com.facturaflow.sales.domain.model.TaxType;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** El adaptador contra PostgreSQL real (Testcontainers), con estadísticas de Hibernate para contar sentencias. */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Import(TestcontainersConfiguration.class)
class JpaSaleRepositoryAdapterTest {

    private static final RequestHash HASH = new RequestHash("a".repeat(64));
    private static final LocalDate DATE = LocalDate.of(2026, 10, 23);
    private static final Instant T0 = Instant.parse("2026-10-23T15:00:00Z");

    @Autowired
    private JpaSaleRepositoryAdapter repository;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics;

    @BeforeEach
    void clean() {
        jdbc.sql("TRUNCATE sale_line_taxes, sale_lines, sales").update();
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private static Sale sale(String reference, Instant createdAt, PaymentTerms terms) {
        return Sale.confirm(SaleId.generate(), new ReferenceCode(reference), DATE, createdAt, customer(),
                List.of(line("2", "50000.00", "10", LineTax.of(TaxType.IVA, "19"), LineTax.of(TaxType.INC, "8")),
                        excludedLine("1", "20000.00")),
                PaymentMethod.BANK_TRANSFER, terms, "Entregar en portería");
    }

    @Test
    @DisplayName("guarda y reconstruye la venta completa: snapshot del cliente, líneas en orden e impuestos")
    void roundTrip() {
        Sale original = sale("VENTA-1", T0, PaymentTerms.credit(DATE.plusDays(30)));

        Sale saved = repository.saveNew(original, HASH);
        StoredSale stored = repository.findStoredByReferenceCode(new ReferenceCode("VENTA-1")).orElseThrow();
        Sale reloaded = stored.sale();

        assertThat(saved.version()).contains(0L);
        assertThat(stored.requestHash()).isEqualTo(HASH);
        assertThat(reloaded.id()).isEqualTo(original.id());
        assertThat(reloaded.customer()).isEqualTo(original.customer());
        assertThat(reloaded.lines()).isEqualTo(original.lines());
        assertThat(reloaded.lines().getFirst().taxes()).extracting(LineTax::type).containsExactly(TaxType.IVA, TaxType.INC);
        assertThat(reloaded.paymentTerms()).isEqualTo(PaymentTerms.credit(DATE.plusDays(30)));
        assertThat(reloaded.total()).isEqualTo(original.total()).isEqualTo(Money.of("134300.00"));
        assertThat(repository.findById(original.id())).isPresent();
    }

    @Test
    @DisplayName("un código de referencia repetido se traduce a la excepción del puerto")
    void duplicateReferenceCode() {
        repository.saveNew(sale("VENTA-1", T0, PaymentTerms.cash()), HASH);

        assertThatThrownBy(() -> repository.saveNew(sale("VENTA-1", T0, PaymentTerms.cash()), HASH))
                .isInstanceOf(DuplicateReferenceCodeException.class);
    }

    @Test
    @DisplayName("consultar por id o código de referencia es una sola sentencia (entity graph)")
    void loadIsOneStatement() {
        Sale saved = repository.saveNew(sale("VENTA-1", T0, PaymentTerms.cash()), HASH);
        statistics.clear();

        repository.findById(saved.id());

        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("el listado va de la más reciente a la más antigua, sin N+1 (4 sentencias por página)")
    void pageNewestFirstWithoutNPlusOne() {
        for (int i = 0; i < 12; i++) {
            repository.saveNew(sale("VENTA-" + i, T0.plusSeconds(i), PaymentTerms.cash()), HASH);
        }
        statistics.clear();

        SalePage page = repository.findPage(null, 0, 10);

        assertThat(page.totalItems()).isEqualTo(12);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.items()).extracting(sale -> sale.referenceCode().value())
                .startsWith("VENTA-11", "VENTA-10", "VENTA-9");
        assertThat(page.items()).allSatisfy(sale -> assertThat(sale.lines()).hasSize(2));
        assertThat(statistics.getPrepareStatementCount()).as("página, conteo, líneas e impuestos").isEqualTo(4);
        assertThat(repository.findPage(SaleStatus.CONFIRMED, 1, 10).items()).hasSize(2);
    }

    @Test
    @DisplayName("la base rechaza un crédito con vencimiento que no es posterior a la venta (defensa en profundidad)")
    void databaseChecksDueDate() {
        Sale saved = repository.saveNew(sale("VENTA-1", T0, PaymentTerms.credit(DATE.plusDays(1))), HASH);

        assertThatThrownBy(() -> jdbc.sql("UPDATE sales SET due_date = sale_date WHERE id = :id")
                .param("id", saved.id().value()).update()).hasMessageContaining("ck_sales_payment_terms");
    }
}
