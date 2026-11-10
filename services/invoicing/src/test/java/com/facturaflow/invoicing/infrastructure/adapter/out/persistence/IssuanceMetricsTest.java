package com.facturaflow.invoicing.infrastructure.adapter.out.persistence;

import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.ISSUE_DATE;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.customer;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.electronicDocument;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.invoicing.TestcontainersConfiguration;
import com.facturaflow.invoicing.application.exception.InvoiceConcurrentModificationException;
import com.facturaflow.invoicing.application.port.out.InvoiceRepository;
import com.facturaflow.invoicing.application.port.out.RequestHash;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.domain.model.InvoiceId;
import com.facturaflow.invoicing.domain.model.PaymentMethod;
import com.facturaflow.invoicing.domain.model.PaymentTerms;
import com.facturaflow.invoicing.domain.model.ReferenceCode;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Métricas de negocio de la emisión (ADR 0020): se cuentan en el adaptador de persistencia SOLO al confirmarse la
 * transacción. PostgreSQL real: un cambio de estado revertido por conflicto de versión no cuenta.
 */
@SpringBootTest(properties = "invoicing.outbox.relay.enabled=false")
@Import(TestcontainersConfiguration.class)
class IssuanceMetricsTest {

    private static final RequestHash HASH = new RequestHash("e".repeat(64));

    @Autowired
    private InvoiceRepository invoices;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE TABLE invoices CASCADE").update();
        jdbc.sql("TRUNCATE TABLE outbox_events").update();
    }

    private Invoice savedInvoice(String reference) {
        return invoices.saveNew(Invoice.create(InvoiceId.generate(), new ReferenceCode(reference), customer(),
                List.of(line("1", "100000.00", "0", "19")), PaymentMethod.CASH, PaymentTerms.cash(), ISSUE_DATE, null),
                HASH);
    }

    private double invoices(String outcome) {
        return meters.get(IssuanceMetrics.INVOICES).tag("outcome", outcome).counter().count();
    }

    @Test
    @DisplayName("las series de facturas y notas crédito existen desde el arranque (el dashboard muestra 0)")
    void seriesExistFromStartup() {
        for (String metric : List.of(IssuanceMetrics.INVOICES, IssuanceMetrics.CREDIT_NOTES)) {
            for (String outcome : List.of("issued", "rejected")) {
                assertThat(meters.find(metric).tag("outcome", outcome).counter()).as(metric + " " + outcome).isNotNull();
            }
        }
    }

    @Test
    @DisplayName("una factura emitida suma un 'issued' y una rechazada un 'rejected'; crearla no cuenta")
    void issuedAndRejectedInvoicesAreCounted() {
        double issuedBefore = invoices("issued");
        double rejectedBefore = invoices("rejected");

        Invoice issued = savedInvoice("VENTA-EMI-1");
        Invoice rejected = savedInvoice("VENTA-EMI-2");
        assertThat(invoices("issued")).as("crear una factura PENDING no cuenta").isEqualTo(issuedBefore);

        issued.markAsIssued(electronicDocument("119000.00"));
        invoices.save(issued);
        rejected.markAsRejected("La DIAN no validó el documento");
        invoices.save(rejected);

        assertThat(invoices("issued")).isEqualTo(issuedBefore + 1);
        assertThat(invoices("rejected")).isEqualTo(rejectedBefore + 1);
    }

    @Test
    @DisplayName("una emisión revertida por conflicto de versión no cuenta")
    void conflictingIssuanceIsNotCounted() {
        Invoice saved = savedInvoice("VENTA-EMI-3");
        Invoice stale = invoices.findById(saved.id()).orElseThrow();
        saved.markAsRejected("primero");
        invoices.save(saved);
        double before = invoices("issued");

        stale.markAsIssued(electronicDocument("119000.00"));
        assertThatThrownBy(() -> invoices.save(stale)).isInstanceOf(InvoiceConcurrentModificationException.class);

        assertThat(invoices("issued")).isEqualTo(before);
    }
}
