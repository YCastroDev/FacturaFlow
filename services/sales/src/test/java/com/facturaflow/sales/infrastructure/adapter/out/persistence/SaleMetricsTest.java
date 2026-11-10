package com.facturaflow.sales.infrastructure.adapter.out.persistence;

import static com.facturaflow.sales.domain.model.SaleFixtures.CREATED_AT;
import static com.facturaflow.sales.domain.model.SaleFixtures.SALE_DATE;
import static com.facturaflow.sales.domain.model.SaleFixtures.SOLD_BY;
import static com.facturaflow.sales.domain.model.SaleFixtures.customer;
import static com.facturaflow.sales.domain.model.SaleFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.sales.TestcontainersConfiguration;
import com.facturaflow.sales.application.exception.DuplicateReferenceCodeException;
import com.facturaflow.sales.application.exception.SaleConcurrentModificationException;
import com.facturaflow.sales.application.port.out.RequestHash;
import com.facturaflow.sales.application.port.out.SaleRepository;
import com.facturaflow.sales.domain.model.InvoiceReference;
import com.facturaflow.sales.domain.model.PaymentMethod;
import com.facturaflow.sales.domain.model.PaymentTerms;
import com.facturaflow.sales.domain.model.ReferenceCode;
import com.facturaflow.sales.domain.model.Sale;
import com.facturaflow.sales.domain.model.SaleId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Métricas de negocio de las ventas (ADR 0020): se cuentan en el adaptador de persistencia SOLO al confirmarse la
 * transacción. PostgreSQL real: una venta que se revierte (referencia duplicada, conflicto de versión) no cuenta.
 */
@SpringBootTest(properties = "sales.outbox.relay.enabled=false")
@Import(TestcontainersConfiguration.class)
class SaleMetricsTest {

    private static final RequestHash HASH = new RequestHash("c".repeat(64));
    private static final InvoiceReference INVOICE = new InvoiceReference(UUID.randomUUID(), "SETP1", "ab".repeat(48));

    @Autowired
    private SaleRepository sales;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE sale_line_taxes, sale_lines, sales, outbox_events").update();
    }

    private Sale confirmedSale(String reference, PaymentMethod paymentMethod) {
        return Sale.confirm(SaleId.generate(), new ReferenceCode(reference), SALE_DATE, CREATED_AT, customer(),
                List.of(line("2", "10000", "0", "19")), paymentMethod, PaymentTerms.cash(), null, SOLD_BY);
    }

    private double confirmed(String paymentMethod) {
        Counter counter = meters.find(SaleMetrics.CONFIRMED).tag("payment_method", paymentMethod).counter();
        return counter == null ? 0 : counter.count();
    }

    private double amount(String paymentMethod) {
        DistributionSummary summary = meters.find(SaleMetrics.AMOUNT).tag("payment_method", paymentMethod).summary();
        return summary == null ? 0 : summary.totalAmount();
    }

    private double outcome(String status) {
        return meters.get(SaleMetrics.INVOICING_OUTCOME).tag("status", status).counter().count();
    }

    private Timer timeToInvoice() {
        return meters.get(SaleMetrics.TIME_TO_INVOICE).timer();
    }

    @Test
    @DisplayName("una venta confirmada suma una venta y su total (COP), por medio de pago")
    void confirmedSaleIsCounted() {
        double before = confirmed("debit_card");
        double amountBefore = amount("debit_card");

        sales.saveNew(confirmedSale("VENTA-M-1", PaymentMethod.DEBIT_CARD), HASH);

        assertThat(confirmed("debit_card")).isEqualTo(before + 1);
        assertThat(amount("debit_card")).as("2 × 10.000 + IVA 19 %").isEqualTo(amountBefore + 23800.0);
    }

    @Test
    @DisplayName("una venta revertida por referencia duplicada no cuenta")
    void rolledBackSaleIsNotCounted() {
        sales.saveNew(confirmedSale("VENTA-M-2", PaymentMethod.CASH), HASH);
        double before = confirmed("cash");

        assertThatThrownBy(() -> sales.saveNew(confirmedSale("VENTA-M-2", PaymentMethod.CASH), HASH))
                .isInstanceOf(DuplicateReferenceCodeException.class);

        assertThat(confirmed("cash")).isEqualTo(before);
    }

    @Test
    @DisplayName("una venta facturada suma un resultado 'invoiced' y su tiempo desde la venta confirmada")
    void invoicedSaleRecordsOutcomeAndTimeToInvoice() {
        Sale saved = sales.saveNew(confirmedSale("VENTA-M-3", PaymentMethod.CASH), HASH);
        double before = outcome("invoiced");
        long timedBefore = timeToInvoice().count();
        double totalBefore = timeToInvoice().totalTime(TimeUnit.MILLISECONDS);

        sales.save(saved.invoiced(INVOICE, CREATED_AT.plus(Duration.ofSeconds(42))));

        assertThat(outcome("invoiced")).isEqualTo(before + 1);
        assertThat(timeToInvoice().count()).isEqualTo(timedBefore + 1);
        assertThat(timeToInvoice().totalTime(TimeUnit.MILLISECONDS) - totalBefore).isEqualTo(42_000.0);
    }

    @Test
    @DisplayName("fallo y luego éxito: un 'invoice_failed' y después un 'invoiced'")
    void failedThenInvoicedCountsBoth() {
        Sale saved = sales.saveNew(confirmedSale("VENTA-M-4", PaymentMethod.CASH), HASH);
        double failedBefore = outcome("invoice_failed");
        double invoicedBefore = outcome("invoiced");

        Sale failed = sales.save(saved.invoiceFailed("La DIAN no validó el documento", null, CREATED_AT));
        sales.save(failed.invoiced(INVOICE, CREATED_AT.plusSeconds(1)));

        assertThat(outcome("invoice_failed")).isEqualTo(failedBefore + 1);
        assertThat(outcome("invoiced")).isEqualTo(invoicedBefore + 1);
    }

    @Test
    @DisplayName("un cambio de estado revertido por conflicto de versión no cuenta")
    void conflictingTransitionIsNotCounted() {
        Sale saved = sales.saveNew(confirmedSale("VENTA-M-5", PaymentMethod.CASH), HASH);
        sales.save(saved.invoiced(INVOICE, CREATED_AT));
        double before = outcome("invoice_failed");

        assertThatThrownBy(() -> sales.save(saved.invoiceFailed("tarde", null, CREATED_AT)))
                .isInstanceOf(SaleConcurrentModificationException.class);

        assertThat(outcome("invoice_failed")).isEqualTo(before);
    }
}
