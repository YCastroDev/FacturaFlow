package com.facturaflow.invoicing.infrastructure.adapter.out.persistence;

import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.ISSUE_DATE;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.customer;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.electronicDocument;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.excludedLine;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.invoicing.TestcontainersConfiguration;
import com.facturaflow.invoicing.application.exception.DuplicateReferenceCodeException;
import com.facturaflow.invoicing.application.exception.InvoiceConcurrentModificationException;
import com.facturaflow.invoicing.application.port.out.InvoiceRepository;
import com.facturaflow.invoicing.domain.model.CustomerSnapshot;
import com.facturaflow.invoicing.domain.model.DocumentType;
import com.facturaflow.invoicing.domain.model.IdentificationDocument;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.domain.model.InvoiceId;
import com.facturaflow.invoicing.domain.model.InvoiceLine;
import com.facturaflow.invoicing.domain.model.InvoiceStatus;
import com.facturaflow.invoicing.domain.model.LineTax;
import com.facturaflow.invoicing.domain.model.Money;
import com.facturaflow.invoicing.domain.model.PaymentMethod;
import com.facturaflow.invoicing.domain.model.PaymentTerms;
import com.facturaflow.invoicing.domain.model.Quantity;
import com.facturaflow.invoicing.domain.model.ReferenceCode;
import com.facturaflow.invoicing.domain.model.TaxType;
import com.facturaflow.invoicing.domain.model.UnitOfMeasure;
import com.facturaflow.invoicing.domain.model.DiscountRate;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.stream.Stream;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Integración contra un PostgreSQL real (Testcontainers) con la migración V2 aplicada.
 * Sin @Transactional en el test: cada save hace commit de verdad, como en producción.
 * El SQL se registra en el log (org.hibernate.SQL) para poder inspeccionar las consultas.
 */
@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.SQL=DEBUG",
        "logging.level.org.hibernate.orm.jdbc.bind=TRACE",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=WARN"
})
@Import(TestcontainersConfiguration.class)
class JpaInvoiceRepositoryAdapterTest {

    @Autowired
    private InvoiceRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("TRUNCATE TABLE invoices CASCADE").update();
    }

    private static Invoice newInvoice(String referenceCode, PaymentTerms terms) {
        return newInvoice(referenceCode, terms, customer(), List.of(
                line("2", "50000.00", "10", LineTax.of(TaxType.IVA, "19"), LineTax.of(TaxType.INC, "8")),
                excludedLine("1", "20000.00"),
                line("3", "1999.99", "5", "5")));
    }

    private static Invoice newInvoice(String referenceCode, PaymentTerms terms, CustomerSnapshot customer,
                                      List<InvoiceLine> lines) {
        return Invoice.create(InvoiceId.generate(), new ReferenceCode(referenceCode), customer, lines,
                PaymentMethod.BANK_TRANSFER, terms, ISSUE_DATE, "Entregar en portería");
    }

    @Test
    @DisplayName("una factura pendiente se recupera igual a la original, con líneas e impuestos múltiples")
    void savesAndLoadsPendingInvoice() {
        Invoice original = newInvoice("SALE-1", PaymentTerms.cash());

        Invoice saved = repository.save(original);
        Invoice loaded = repository.findById(original.id()).orElseThrow();

        assertThat(loaded).usingRecursiveComparison().ignoringFields("version").isEqualTo(original);
        assertThat(loaded.version()).contains(0L);
        assertThat(saved.version()).contains(0L);
        assertThat(loaded.lines().getFirst().taxes())
                .containsExactly(LineTax.of(TaxType.IVA, "19"), LineTax.of(TaxType.INC, "8"));
        assertThat(loaded.total()).isEqualTo(Money.of("140284.97"));
    }

    @Test
    @DisplayName("al emitir y volver a guardar se conservan el documento electrónico y el total oficial")
    void savesIssuedInvoice() {
        Invoice saved = repository.save(newInvoice("SALE-1", PaymentTerms.cash()));
        saved.markAsIssued(electronicDocument("140285.00"));

        Invoice issued = repository.save(saved);
        Invoice loaded = repository.findById(saved.id()).orElseThrow();

        assertThat(issued.version()).contains(1L);
        assertThat(loaded.status()).isEqualTo(InvoiceStatus.ISSUED);
        assertThat(loaded.electronicDocument()).isEqualTo(saved.electronicDocument());
        assertThat(loaded.electronicDocument().orElseThrow().officialTotal()).isEqualTo(Money.of("140285.00"));
        assertThat(loaded.hasOfficialTotalDiscrepancy()).isTrue();
    }

    @Test
    @DisplayName("una factura rechazada conserva su motivo")
    void savesRejectedInvoice() {
        Invoice saved = repository.save(newInvoice("SALE-1", PaymentTerms.cash()));
        saved.markAsRejected("Regla FAD06: NIT inválido");

        repository.save(saved);

        assertThat(repository.findById(saved.id()).orElseThrow().rejectionReason())
                .hasValue("Regla FAD06: NIT inválido");
    }

    @Test
    @DisplayName("una factura se encuentra por el código de referencia de su venta")
    void findsByReferenceCode() {
        Invoice original = repository.save(newInvoice("SALE-42", PaymentTerms.cash()));

        assertThat(repository.findByReferenceCode(new ReferenceCode("SALE-42")))
                .get().extracting(Invoice::id).isEqualTo(original.id());
        assertThat(repository.findByReferenceCode(new ReferenceCode("SALE-404"))).isEmpty();
        assertThat(repository.findById(InvoiceId.generate())).isEmpty();
    }

    @Test
    @DisplayName("una segunda factura para la misma venta se rechaza con una excepción propia")
    void rejectsDuplicateReferenceCode() {
        repository.save(newInvoice("SALE-1", PaymentTerms.cash()));
        Invoice duplicate = newInvoice("SALE-1", PaymentTerms.cash());

        assertThatThrownBy(() -> repository.save(duplicate))
                .isInstanceOf(DuplicateReferenceCodeException.class)
                .hasMessageContaining("SALE-1");
        assertThat(jdbc.sql("SELECT count(*) FROM invoices").query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT count(*) FROM invoice_lines").query(Long.class).single()).isEqualTo(3L);
    }

    @Test
    @DisplayName("si dos copias de una factura se modifican en paralelo, la segunda en guardarse falla")
    void optimisticLocking() {
        Invoice saved = repository.save(newInvoice("SALE-1", PaymentTerms.cash()));
        Invoice copyA = repository.findById(saved.id()).orElseThrow();
        Invoice copyB = repository.findById(saved.id()).orElseThrow();

        copyA.markAsIssued(electronicDocument("140284.97"));
        repository.save(copyA);
        copyB.markAsRejected("rechazo con datos viejos");

        assertThatThrownBy(() -> repository.save(copyB))
                .isInstanceOf(InvoiceConcurrentModificationException.class);
        assertThat(repository.findById(saved.id()).orElseThrow().status()).isEqualTo(InvoiceStatus.ISSUED);
    }

    static Stream<Arguments> paymentTerms() {
        return Stream.of(
                Arguments.of("contado", PaymentTerms.cash()),
                Arguments.of("crédito a 30 días", PaymentTerms.credit(ISSUE_DATE.plusDays(30))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("paymentTerms")
    @DisplayName("la forma de pago se reconstruye correctamente")
    void rebuildsPaymentTerms(String description, PaymentTerms terms) {
        Invoice saved = repository.save(newInvoice("SALE-1", terms));

        assertThat(repository.findById(saved.id()).orElseThrow().paymentTerms()).isEqualTo(terms);
    }

    @Test
    @DisplayName("cargar una factura con varias líneas e impuestos ejecuta una sola consulta (sin N+1)")
    void loadsAggregateWithSingleQuery() {
        Invoice saved = repository.save(newInvoice("SALE-1", PaymentTerms.cash()));
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        Invoice loaded = repository.findById(saved.id()).orElseThrow();

        assertThat(loaded.lines()).hasSize(3);
        assertThat(loaded.lines()).flatExtracting(InvoiceLine::taxes).hasSize(3);   // IVA+INC, ninguno, IVA
        assertThat(statistics.getPrepareStatementCount()).as("sentencias SQL preparadas").isEqualTo(1);
    }

    @Test
    @DisplayName("guardar un cambio de estado ejecuta solo una lectura y una actualización (sin N+1)")
    void updatesAggregateWithTwoStatements() {
        Invoice saved = repository.save(newInvoice("SALE-1", PaymentTerms.cash()));
        saved.markAsIssued(electronicDocument("140284.97"));
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        Invoice issued = repository.save(saved);

        assertThat(issued.lines()).hasSize(3);
        assertThat(statistics.getPrepareStatementCount()).as("SELECT con joins + UPDATE de invoices").isEqualTo(2);
    }

    @Test
    @DisplayName("todos los valores de los enums del dominio son aceptados por los CHECK de la base")
    void everyDomainEnumValueIsAcceptedByTheDatabase() {
        int sequence = 0;
        for (DocumentType type : DocumentType.values()) {
            IdentificationDocument document = type == DocumentType.NIT
                    ? IdentificationDocument.nit("890903938", 8)
                    : IdentificationDocument.of(type, "1020304050");
            repository.save(newInvoice("DOC-" + sequence++, PaymentTerms.cash(),
                    CustomerSnapshot.person(document, "Ana", null), List.of(line("1", "1000", "0", "19"))));
        }
        for (UnitOfMeasure unit : UnitOfMeasure.values()) {
            InvoiceLine line = new InvoiceLine("SKU-1", "Producto", Quantity.of("1"), Money.of("1000"),
                    DiscountRate.NONE, List.of(LineTax.of(TaxType.IVA, "19")), unit, false);
            repository.save(newInvoice("UNIT-" + sequence++, PaymentTerms.cash(), customer(), List.of(line)));
        }
        for (PaymentMethod method : PaymentMethod.values()) {
            repository.save(Invoice.create(InvoiceId.generate(), new ReferenceCode("PM-" + sequence++), customer(),
                    List.of(line("1", "1000", "0", LineTax.of(TaxType.INC, "8"))), method, PaymentTerms.cash(),
                    ISSUE_DATE, null));
        }
        for (InvoiceStatus status : List.of(InvoiceStatus.ISSUED, InvoiceStatus.REJECTED)) {
            Invoice invoice = repository.save(newInvoice("ST-" + sequence++, PaymentTerms.cash()));
            if (status == InvoiceStatus.ISSUED) {
                invoice.markAsIssued(electronicDocument("1.00"));
            } else {
                invoice.markAsRejected("motivo");
            }
            repository.save(invoice);
        }

        long expected = DocumentType.values().length + UnitOfMeasure.values().length
                + PaymentMethod.values().length + 2;
        assertThat(jdbc.sql("SELECT count(*) FROM invoices").query(Long.class).single()).isEqualTo(expected);
    }
}
