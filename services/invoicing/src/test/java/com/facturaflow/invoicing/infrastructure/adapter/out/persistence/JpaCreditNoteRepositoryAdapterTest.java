package com.facturaflow.invoicing.infrastructure.adapter.out.persistence;

import static com.facturaflow.invoicing.domain.model.CreditNoteFixtures.annulment;
import static com.facturaflow.invoicing.domain.model.CreditNoteFixtures.credit;
import static com.facturaflow.invoicing.domain.model.CreditNoteFixtures.creditNoteDocument;
import static com.facturaflow.invoicing.domain.model.CreditNoteFixtures.partialReturn;
import static com.facturaflow.invoicing.domain.model.CreditNoteFixtures.pendingInvoiceToCredit;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.electronicDocument;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.facturaflow.invoicing.TestcontainersConfiguration;
import com.facturaflow.invoicing.application.exception.CreditNoteConcurrentModificationException;
import com.facturaflow.invoicing.application.exception.DuplicateCreditNoteReferenceCodeException;
import com.facturaflow.invoicing.application.exception.InvoiceConcurrentModificationException;
import com.facturaflow.invoicing.application.port.out.CreditNoteRepository;
import com.facturaflow.invoicing.application.port.out.InvoiceRepository;
import com.facturaflow.invoicing.application.port.out.RequestHash;
import com.facturaflow.invoicing.domain.model.CreditBalance;
import com.facturaflow.invoicing.domain.model.CreditNote;
import com.facturaflow.invoicing.domain.model.CreditNoteId;
import com.facturaflow.invoicing.domain.model.CreditNoteReason;
import com.facturaflow.invoicing.domain.model.CreditNoteStatus;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.domain.model.Money;
import com.facturaflow.invoicing.domain.model.ReferenceCode;
import jakarta.persistence.EntityManagerFactory;
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

/**
 * Integración contra PostgreSQL real (Testcontainers), como {@code JpaInvoiceRepositoryAdapterTest}:
 * sin @Transactional en el test, cada escritura hace commit de verdad.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Import(TestcontainersConfiguration.class)
class JpaCreditNoteRepositoryAdapterTest {

    private static final RequestHash HASH = new RequestHash("c".repeat(64));

    @Autowired
    private CreditNoteRepository repository;

    @Autowired
    private InvoiceRepository invoices;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Invoice invoice;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE TABLE invoices CASCADE").update();
        Invoice saved = invoices.saveNew(pendingInvoiceToCredit(), new RequestHash("a".repeat(64)));
        saved.markAsIssued(electronicDocument("147475.01"));
        invoice = invoices.save(saved);
    }

    private CreditBalance balance() {
        return CreditBalance.of(invoices.findById(invoice.id()).orElseThrow(), repository.findByInvoiceId(invoice.id()));
    }

    private long invoiceVersion() {
        return invoices.findById(invoice.id()).orElseThrow().version().orElseThrow();
    }

    private CreditNote saveNew(CreditNote note) {
        return repository.saveNew(note, HASH, invoiceVersion());
    }

    private Statistics statistics() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        return statistics;
    }

    @Test
    @DisplayName("una nota se recupera igual a la original, con sus líneas copiadas de la factura e impuestos múltiples")
    void savesAndLoadsNote() {
        CreditNote original = partialReturn(balance(), "DEV-1", credit(1, "1"), credit(2, "0.5"));

        CreditNote saved = saveNew(original);
        CreditNote loaded = repository.findById(original.id()).orElseThrow();

        assertThat(loaded).usingRecursiveComparison().ignoringFields("version").isEqualTo(original);
        assertThat(saved.version()).contains(0L);
        assertThat(loaded.lines().getFirst().creditedLine().taxes()).hasSize(2);
        assertThat(loaded.total()).isEqualTo(Money.of("49158.34"));
    }

    @Test
    @DisplayName("insertar una nota sube la versión de su factura, sin cambiar nada más de ella")
    void saveNewIncrementsInvoiceVersion() {
        long before = invoiceVersion();

        saveNew(partialReturn(balance(), "DEV-1", credit(1, "1")));

        Invoice after = invoices.findById(invoice.id()).orElseThrow();
        assertThat(after.version()).contains(before + 1);
        assertThat(after).usingRecursiveComparison().ignoringFields("version").isEqualTo(invoice);
    }

    @Test
    @DisplayName("si la factura cambió desde la lectura (otra nota), la nota no se inserta: bloqueo optimista")
    void staleInvoiceVersionIsRejected() {
        long readVersion = invoiceVersion();
        CreditBalance snapshot = balance();
        repository.saveNew(partialReturn(snapshot, "DEV-A", credit(1, "2")), HASH, readVersion);

        assertThatThrownBy(() -> repository.saveNew(partialReturn(snapshot, "DEV-B", credit(1, "2")),
                new RequestHash("d".repeat(64)), readVersion))
                .isInstanceOf(InvoiceConcurrentModificationException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM credit_notes").query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    @DisplayName("una segunda nota con el mismo código se rechaza con una excepción propia y no sube la versión")
    void rejectsDuplicateReferenceCode() {
        saveNew(partialReturn(balance(), "DEV-1", credit(1, "1")));
        long versionBefore = invoiceVersion();

        assertThatThrownBy(() -> saveNew(partialReturn(balance(), "DEV-1", credit(2, "1"))))
                .isInstanceOf(DuplicateCreditNoteReferenceCodeException.class)
                .hasMessageContaining("DEV-1");
        assertThat(invoiceVersion()).isEqualTo(versionBefore);   // la transacción completa se revirtió
        assertThat(jdbc.sql("SELECT count(*) FROM credit_note_lines").query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    @DisplayName("al emitir y volver a guardar se conservan el documento (CUDE) y el total oficial")
    void savesIssuedNote() {
        CreditNote saved = saveNew(partialReturn(balance(), "DEV-1", credit(1, "1")));
        saved.markAsIssued(creditNoteDocument(Money.of("39158.33")));

        CreditNote issued = repository.save(saved);

        CreditNote loaded = repository.findById(saved.id()).orElseThrow();
        assertThat(issued.version()).contains(1L);
        assertThat(loaded.status()).isEqualTo(CreditNoteStatus.ISSUED);
        assertThat(loaded.electronicDocument()).isEqualTo(issued.electronicDocument());
        assertThat(jdbc.sql("SELECT edoc_unique_code FROM credit_notes").query(String.class).single())
                .isEqualTo("b659dc4457ab1b68".repeat(6));
    }

    @Test
    @DisplayName("si dos copias de una nota se modifican en paralelo, la segunda en guardarse falla")
    void optimisticLockingOnTheNote() {
        CreditNote saved = saveNew(partialReturn(balance(), "DEV-1", credit(1, "1")));
        CreditNote copyA = repository.findById(saved.id()).orElseThrow();
        CreditNote copyB = repository.findById(saved.id()).orElseThrow();
        copyA.markAsIssued(creditNoteDocument(Money.of("39158.33")));
        repository.save(copyA);
        copyB.markAsRejected("rechazo con datos viejos");

        assertThatThrownBy(() -> repository.save(copyB)).isInstanceOf(CreditNoteConcurrentModificationException.class);
    }

    @Test
    @DisplayName("una nota se busca por código de referencia con su huella; las de una factura se listan en orden de creación")
    void findsByReferenceCodeAndInvoice() {
        CreditNote first = saveNew(partialReturn(balance(), "DEV-1", credit(1, "1")));
        CreditNote second = saveNew(annulment(balance(), "ANU-1"));

        assertThat(repository.findStoredByReferenceCode(new ReferenceCode("DEV-1"))).get()
                .satisfies(stored -> assertThat(stored.requestHash()).isEqualTo(HASH));
        assertThat(repository.findStoredByReferenceCode(new ReferenceCode("DEV-404"))).isEmpty();
        assertThat(repository.findById(CreditNoteId.generate())).isEmpty();
        assertThat(repository.findByInvoiceId(invoice.id())).extracting(CreditNote::id)
                .containsExactly(first.id(), second.id());
    }

    @Test
    @DisplayName("cargar una nota, o todas las de una factura, ejecuta una sola consulta (sin N+1)")
    void loadsWithSingleQuery() {
        CreditNote note = saveNew(partialReturn(balance(), "DEV-1", credit(1, "1"), credit(2, "0.5")));
        saveNew(annulment(balance(), "ANU-1"));

        Statistics statistics = statistics();
        repository.findById(note.id());
        assertThat(statistics.getPrepareStatementCount()).as("findById").isEqualTo(1);

        statistics = statistics();
        List<CreditNote> all = repository.findByInvoiceId(invoice.id());
        assertThat(all).hasSize(2);
        assertThat(statistics.getPrepareStatementCount()).as("findByInvoiceId").isEqualTo(1);
    }

    @Test
    @DisplayName("insertar una nota ejecuta: UPDATE de la versión de la factura + INSERT de nota, líneas e impuestos")
    void saveNewStatements() {
        CreditNote note = partialReturn(balance(), "DEV-1", credit(1, "1"));
        long version = invoiceVersion();

        Statistics statistics = statistics();
        repository.saveNew(note, HASH, version);

        // UPDATE invoices + INSERT credit_notes + INSERT credit_note_lines + 2 INSERT credit_note_line_taxes
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("la base rechaza estados incoherentes y una línea acreditada dos veces en la misma nota")
    void databaseChecks() {
        CreditNote saved = saveNew(partialReturn(balance(), "DEV-1", credit(1, "1")));

        assertThatThrownBy(() -> jdbc.sql("UPDATE credit_notes SET status = 'ISSUED' WHERE id = ?")
                .param(saved.id().value()).update())
                .hasMessageContaining("ck_credit_notes_state_coherence");
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO credit_note_lines (id, credit_note_id, sort_order, invoice_line_number, code, name,
                    quantity, unit_price, discount_rate, unit_of_measure, iva_excluded)
                VALUES (gen_random_uuid(), ?, 1, 1, 'SKU', 'X', 1, 1, 0, 'UNIT', false)""")
                .param(saved.id().value()).update())
                .hasMessageContaining("uk_credit_note_lines_invoice_line");
    }

    @Test
    @DisplayName("todos los motivos y estados del dominio son aceptados por los CHECK de la base")
    void everyDomainEnumValueIsAccepted() {
        CreditNote partial = saveNew(partialReturn(balance(), "DEV-1", credit(1, "1")));
        CreditNote annulment = saveNew(annulment(balance(), "ANU-1"));
        partial.markAsRejected("motivo");
        annulment.markAsIssued(creditNoteDocument(annulment.total()));

        repository.save(partial);
        repository.save(annulment);

        assertThat(repository.findByInvoiceId(invoice.id()))
                .extracting(CreditNote::reason, CreditNote::status)
                .containsExactlyInAnyOrder(
                        tuple(CreditNoteReason.PARTIAL_RETURN, CreditNoteStatus.REJECTED),
                        tuple(CreditNoteReason.TOTAL_ANNULMENT, CreditNoteStatus.ISSUED));
        assertThat(CreditNoteReason.values()).hasSize(2);
        assertThat(CreditNoteStatus.values()).hasSize(3);   // PENDING se cubre en las demás pruebas
    }
}
