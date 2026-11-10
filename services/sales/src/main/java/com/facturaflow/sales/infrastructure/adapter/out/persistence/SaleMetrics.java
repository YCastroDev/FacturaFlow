package com.facturaflow.sales.infrastructure.adapter.out.persistence;

import com.facturaflow.sales.domain.model.InvoicingOutcome;
import com.facturaflow.sales.domain.model.Sale;
import com.facturaflow.sales.domain.model.SaleStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Métricas de negocio de las ventas (ADR 0020, D4 y D5). Se registran en el adaptador de persistencia porque es el
 * único punto por donde pasan todas las ventas y todas las transiciones de estado (API y mensajería), y SOLO al
 * confirmarse la transacción: una venta que se revierte (referencia duplicada, conflicto de versión) no cuenta.
 *
 * <p>Etiquetas de baja cardinalidad: nunca el cliente, el producto ni el {@code referenceCode}. Los nombres los consulta
 * el dashboard de negocio ({@code infra/grafana/dashboards}); {@code BusinessMetricsContractTest} lo verifica.
 */
@Component
class SaleMetrics {

    static final String CONFIRMED = "sales.confirmed";
    static final String AMOUNT = "sales.amount";
    static final String INVOICING_OUTCOME = "sales.invoicing.outcome";
    static final String TIME_TO_INVOICE = "sales.time_to_invoice";

    private final MeterRegistry registry;

    SaleMetrics(MeterRegistry registry) {
        this.registry = registry;
        // Las series del resultado existen desde el arranque (en cero): el dashboard muestra 0, no "sin datos".
        for (SaleStatus status : new SaleStatus[] {SaleStatus.INVOICED, SaleStatus.INVOICE_FAILED}) {
            outcomeCounter(status);
        }
        timeToInvoice();
    }

    /** Una venta nueva confirmada: cuenta y monto (COP), por medio de pago. */
    void confirmed(Sale sale) {
        String paymentMethod = sale.paymentMethod().name().toLowerCase(Locale.ROOT);
        afterCommit(() -> {
            Counter.builder(CONFIRMED).description("Ventas confirmadas")
                    .tag("payment_method", paymentMethod).register(registry).increment();
            DistributionSummary.builder(AMOUNT).description("Total de las ventas confirmadas, en COP")
                    .tag("payment_method", paymentMethod).register(registry)
                    .record(sale.total().amount().doubleValue());
        });
    }

    /**
     * Una venta que cambió de estado. Solo cuentan las llegadas a INVOICED o INVOICE_FAILED; el tiempo hasta la factura
     * va desde la venta confirmada hasta el instante del resultado (también si antes había fallado: es lo que esperó el
     * negocio).
     */
    void transitioned(SaleStatus from, Sale sale) {
        SaleStatus to = sale.status();
        if (from == to || (to != SaleStatus.INVOICED && to != SaleStatus.INVOICE_FAILED)) {
            return;
        }
        afterCommit(() -> {
            outcomeCounter(to).increment();
            if (to == SaleStatus.INVOICED) {
                sale.invoicing().map(InvoicingOutcome::at).ifPresent(at -> {
                    Duration elapsed = Duration.between(sale.createdAt(), at);
                    // Relojes de servicios distintos: un desfase nunca da un tiempo negativo.
                    timeToInvoice().record(elapsed.isNegative() ? Duration.ZERO : elapsed);
                });
            }
        });
    }

    private Counter outcomeCounter(SaleStatus status) {
        return Counter.builder(INVOICING_OUTCOME).description("Ventas que terminaron facturadas o con la facturación fallida")
                .tag("status", status.name().toLowerCase(Locale.ROOT)).register(registry);
    }

    private Timer timeToInvoice() {
        return Timer.builder(TIME_TO_INVOICE).description("Tiempo desde la venta confirmada hasta su factura emitida")
                .publishPercentileHistogram().register(registry);
    }

    /** Dentro de una transacción, al confirmarse; fuera de una (no debería pasar), enseguida. */
    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
