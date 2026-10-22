package com.facturaflow.invoicing.infrastructure.adapter.out.persistence;

import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.ISSUE_DATE;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.VALID_CUFE;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.customer;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.customerWithLocation;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.electronicDocument;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.excludedLine;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.line;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.naturalPerson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.invoicing.TestcontainersConfiguration;
import com.facturaflow.invoicing.application.exception.DuplicateReferenceCodeException;
import com.facturaflow.invoicing.application.exception.InvoiceConcurrentModificationException;
import com.facturaflow.invoicing.application.port.out.InvoiceRepository;
import com.facturaflow.invoicing.application.port.out.RequestHash;
import com.facturaflow.invoicing.application.port.out.StoredInvoice;
import com.facturaflow.invoicing.domain.model.CustomerSnapshot;
import com.facturaflow.invoicing.domain.model.DiscountRate;
import com.facturaflow.invoicing.domain.model.DocumentType;
import com.facturaflow.invoicing.domain.model.ElectronicDocument;
import com.facturaflow.invoicing.domain.model.FiscalProfile;
import com.facturaflow.invoicing.domain.model.FiscalResponsibility;
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
import com.facturaflow.invoicing.domain.model.TaxScheme;
import com.facturaflow.invoicing.domain.model.TaxType;
import com.facturaflow.invoicing.domain.model.UnitOfMeasure;
import jakarta.persistence.EntityManagerFactory;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
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
 * Integración contra un PostgreSQL real (Testcontainers) con las migraciones aplicadas.
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

    private static final RequestHash HASH = new RequestHash("a".repeat(64));
    private static final RequestHash OTHER_HASH = new RequestHash("b".repeat(64));

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

        Invoice saved = repository.saveNew(original, HASH);
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
        Invoice saved = repository.saveNew(newInvoice("SALE-1", PaymentTerms.cash()), HASH);
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
    @DisplayName("la factura que devuelve save es idéntica a la que se relee, aunque el instante traiga nanosegundos")
    void savedInstanceMatchesReloadedOne() {
        Invoice saved = repository.saveNew(newInvoice("SALE-1", PaymentTerms.cash()), HASH);
        saved.markAsIssued(new ElectronicDocument("SETP1", VALID_CUFE, URI.create("https://sandbox.example/a.pdf"),
                Instant.parse("2026-10-20T15:30:00.123456789Z"), Money.of("140284.97")));

        Invoice issued = repository.save(saved);

        assertThat(issued.electronicDocument().orElseThrow().validatedAt())
                .isEqualTo(Instant.parse("2026-10-20T15:30:00.123456Z"));
        assertThat(repository.findById(saved.id()).orElseThrow().electronicDocument())
                .isEqualTo(issued.electronicDocument());
    }

    @Test
    @DisplayName("los datos fiscales y de ubicación del cliente se guardan y recuperan, incluido el arreglo de responsabilidades")
    void savesCustomerTaxData() {
        Invoice saved = repository.saveNew(newInvoice("SALE-1", PaymentTerms.cash(), customerWithLocation(),
                List.of(line("1", "1000", "0", "19"))), HASH);

        CustomerSnapshot loaded = repository.findById(saved.id()).orElseThrow().customer();

        assertThat(loaded).isEqualTo(customerWithLocation());
        assertThat(jdbc.sql("SELECT array_to_string(customer_fiscal_responsibilities, ',') FROM invoices")
                .query(String.class).single().split(",")).containsExactlyInAnyOrder("LARGE_TAXPAYER", "SELF_WITHHOLDER");
    }

    @Test
    @DisplayName("una persona natural sin ubicación se guarda y recupera igual")
    void savesNaturalPersonWithoutLocation() {
        Invoice saved = repository.saveNew(newInvoice("SALE-1", PaymentTerms.cash(), naturalPerson(),
                List.of(line("1", "1000", "0", "19"))), HASH);

        assertThat(repository.findById(saved.id()).orElseThrow().customer()).isEqualTo(naturalPerson());
    }

    @Test
    @DisplayName("la base rechaza un cliente cuyo tipo de persona no corresponde con sus nombres")
    void databaseRejectsInconsistentPersonType() {
        Invoice saved = repository.saveNew(newInvoice("SALE-1", PaymentTerms.cash()), HASH);

        assertThatThrownBy(() -> jdbc.sql("UPDATE invoices SET customer_person_type = 'NATURAL_PERSON' WHERE id = ?")
                .param(saved.id().value()).update())
                .hasMessageContaining("ck_invoices_customer_person_name");
        assertThatThrownBy(() -> jdbc.sql("UPDATE invoices SET customer_fiscal_responsibilities = "
                        + "ARRAY['NOT_RESPONSIBLE', 'LARGE_TAXPAYER'] WHERE id = ?")
                .param(saved.id().value()).update())
                .hasMessageContaining("ck_invoices_customer_fiscal_responsibilities");
    }

    @Test
    @DisplayName("una factura rechazada conserva su motivo")
    void savesRejectedInvoice() {
        Invoice saved = repository.saveNew(newInvoice("SALE-1", PaymentTerms.cash()), HASH);
        saved.markAsRejected("Regla FAD06: NIT inválido");

        repository.save(saved);

        assertThat(repository.findById(saved.id()).orElseThrow().rejectionReason())
                .hasValue("Regla FAD06: NIT inválido");
    }

    @Test
    @DisplayName("una factura se encuentra por el código de referencia de su venta")
    void findsByReferenceCode() {
        Invoice original = repository.saveNew(newInvoice("SALE-42", PaymentTerms.cash()), HASH);

        assertThat(repository.findByReferenceCode(new ReferenceCode("SALE-42")))
                .get().extracting(Invoice::id).isEqualTo(original.id());
        assertThat(repository.findByReferenceCode(new ReferenceCode("SALE-404"))).isEmpty();
        assertThat(repository.findById(InvoiceId.generate())).isEmpty();
    }

    @Test
    @DisplayName("una segunda factura para la misma venta se rechaza con una excepción propia")
    void rejectsDuplicateReferenceCode() {
        repository.saveNew(newInvoice("SALE-1", PaymentTerms.cash()), HASH);
        Invoice duplicate = newInvoice("SALE-1", PaymentTerms.cash());

        assertThatThrownBy(() -> repository.saveNew(duplicate, OTHER_HASH))
                .isInstanceOf(DuplicateReferenceCodeException.class)
                .hasMessageContaining("SALE-1");
        assertThat(jdbc.sql("SELECT count(*) FROM invoices").query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT count(*) FROM invoice_lines").query(Long.class).single()).isEqualTo(3L);
    }

    @Test
    @DisplayName("si dos copias de una factura se modifican en paralelo, la segunda en guardarse falla")
    void optimisticLocking() {
        Invoice saved = repository.saveNew(newInvoice("SALE-1", PaymentTerms.cash()), HASH);
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
        Invoice saved = repository.saveNew(newInvoice("SALE-1", terms), HASH);

        assertThat(repository.findById(saved.id()).orElseThrow().paymentTerms()).isEqualTo(terms);
    }

    @Test
    @DisplayName("cargar una factura con varias líneas e impuestos ejecuta una sola consulta (sin N+1)")
    void loadsAggregateWithSingleQuery() {
        Invoice saved = repository.saveNew(newInvoice("SALE-1", PaymentTerms.cash()), HASH);
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
        Invoice saved = repository.saveNew(newInvoice("SALE-1", PaymentTerms.cash()), HASH);
        saved.markAsIssued(electronicDocument("140284.97"));
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        Invoice issued = repository.save(saved);

        assertThat(issued.lines()).hasSize(3);
        assertThat(statistics.getPrepareStatementCount()).as("SELECT con joins + UPDATE de invoices").isEqualTo(2);
    }

    @Test
    @DisplayName("la huella de la petición se guarda con la factura y se recupera junto a ella en una sola consulta")
    void storesRequestHash() {
        Invoice saved = repository.saveNew(newInvoice("SALE-1", PaymentTerms.cash()), HASH);
        saved.markAsIssued(electronicDocument("140284.97"));
        repository.save(saved);   // un cambio de estado no toca la huella
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        StoredInvoice stored = repository.findStoredByReferenceCode(new ReferenceCode("SALE-1")).orElseThrow();

        assertThat(stored.requestHash()).isEqualTo(HASH);
        assertThat(stored.invoice().id()).isEqualTo(saved.id());
        assertThat(stored.invoice().lines()).hasSize(3);
        assertThat(statistics.getPrepareStatementCount()).as("sentencias SQL preparadas").isEqualTo(1);
        assertThat(repository.findStoredByReferenceCode(new ReferenceCode("SALE-404"))).isEmpty();
    }

    @Test
    @DisplayName("la base rechaza una huella que no tenga el formato SHA-256 hexadecimal")
    void databaseRejectsMalformedHash() {
        Invoice saved = repository.saveNew(newInvoice("SALE-1", PaymentTerms.cash()), HASH);

        assertThatThrownBy(() -> jdbc.sql("UPDATE invoices SET request_hash = 'XYZ' WHERE id = ?")
                .param(saved.id().value()).update())
                .hasMessageContaining("ck_invoices_request_hash");
    }

    @Test
    @DisplayName("crear y actualizar no se confunden: saveNew exige una factura nueva y save una ya guardada")
    void saveNewAndSaveAreNotInterchangeable() {
        Invoice fresh = newInvoice("SALE-1", PaymentTerms.cash());
        assertThatThrownBy(() -> repository.save(fresh)).isInstanceOf(IllegalArgumentException.class);

        Invoice saved = repository.saveNew(fresh, HASH);
        assertThatThrownBy(() -> repository.saveNew(saved, HASH)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("todos los valores de los enums del dominio son aceptados por los CHECK de la base")
    void everyDomainEnumValueIsAcceptedByTheDatabase() {
        int sequence = 0;
        for (DocumentType type : DocumentType.values()) {
            IdentificationDocument document = type == DocumentType.NIT
                    ? IdentificationDocument.nit("890903938", 8)
                    : IdentificationDocument.of(type, "1020304050");
            repository.saveNew(newInvoice("DOC-" + sequence++, PaymentTerms.cash(),
                    CustomerSnapshot.person(document, "Ana", FiscalProfile.notResponsible(), null),
                    List.of(line("1", "1000", "0", "19"))), HASH);
        }
        for (UnitOfMeasure unit : UnitOfMeasure.values()) {
            InvoiceLine line = new InvoiceLine("SKU-1", "Producto", Quantity.of("1"), Money.of("1000"),
                    DiscountRate.NONE, List.of(LineTax.of(TaxType.IVA, "19")), unit, false);
            repository.saveNew(newInvoice("UNIT-" + sequence++, PaymentTerms.cash(), customer(), List.of(line)), HASH);
        }
        for (FiscalResponsibility responsibility : FiscalResponsibility.values()) {
            for (TaxScheme scheme : TaxScheme.values()) {
                CustomerSnapshot company = CustomerSnapshot.company(IdentificationDocument.nit("890903938", 8),
                        "Empresa", new FiscalProfile(scheme, Set.of(responsibility)), null);
                repository.saveNew(newInvoice("FR-" + sequence++, PaymentTerms.cash(), company,
                        List.of(line("1", "1000", "0", "19"))), HASH);
            }
        }
        for (PaymentMethod method : PaymentMethod.values()) {
            repository.saveNew(Invoice.create(InvoiceId.generate(), new ReferenceCode("PM-" + sequence++), customer(),
                    List.of(line("1", "1000", "0", LineTax.of(TaxType.INC, "8"))), method, PaymentTerms.cash(),
                    ISSUE_DATE, null), HASH);
        }
        for (InvoiceStatus status : List.of(InvoiceStatus.ISSUED, InvoiceStatus.REJECTED)) {
            Invoice invoice = repository.saveNew(newInvoice("ST-" + sequence++, PaymentTerms.cash()), HASH);
            if (status == InvoiceStatus.ISSUED) {
                invoice.markAsIssued(electronicDocument("1.00"));
            } else {
                invoice.markAsRejected("motivo");
            }
            repository.save(invoice);
        }

        long expected = DocumentType.values().length + UnitOfMeasure.values().length
                + FiscalResponsibility.values().length * TaxScheme.values().length
                + PaymentMethod.values().length + 2;
        assertThat(jdbc.sql("SELECT count(*) FROM invoices").query(Long.class).single()).isEqualTo(expected);
    }
}
