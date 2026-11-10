package com.facturaflow.invoicing.infrastructure.adapter.out.persistence;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Métricas de negocio de la emisión (ADR 0020, D4 y D5): facturas y notas crédito emitidas o rechazadas por la DIAN.
 * Se registran en el adaptador de persistencia (el único punto por donde pasan todas las transiciones, por la API o
 * por el consumidor de eventos) y SOLO al confirmarse la transacción: un conflicto de versión no cuenta.
 *
 * <p>Etiquetas de baja cardinalidad (solo el resultado). Los nombres los consulta el dashboard de negocio
 * ({@code infra/grafana/dashboards}); {@code BusinessMetricsContractTest} lo verifica.
 */
@Component
class IssuanceMetrics {

    static final String INVOICES = "invoicing.invoices.issuance";
    static final String CREDIT_NOTES = "invoicing.credit_notes.issuance";
    static final String ISSUED = "issued";
    static final String REJECTED = "rejected";

    private final MeterRegistry registry;

    IssuanceMetrics(MeterRegistry registry) {
        this.registry = registry;
        // Las series existen desde el arranque (en cero): el dashboard muestra 0, no "sin datos".
        for (String metric : new String[] {INVOICES, CREDIT_NOTES}) {
            counter(metric, ISSUED);
            counter(metric, REJECTED);
        }
    }

    /** Una factura que cambió de estado ({@code PENDING}, {@code ISSUED} o {@code REJECTED}, por nombre). */
    void invoiceTransitioned(String from, String to) {
        record(INVOICES, from, to);
    }

    /** Una nota crédito que cambió de estado (mismos estados que la factura). */
    void creditNoteTransitioned(String from, String to) {
        record(CREDIT_NOTES, from, to);
    }

    private void record(String metric, String from, String to) {
        if (from.equals(to) || !"PENDING".equals(from)) {
            return;
        }
        String outcome = switch (to) {
            case "ISSUED" -> ISSUED;
            case "REJECTED" -> REJECTED;
            default -> null;
        };
        if (outcome != null) {
            afterCommit(() -> counter(metric, outcome).increment());
        }
    }

    private Counter counter(String metric, String outcome) {
        return Counter.builder(metric).description("Documentos electrónicos emitidos o rechazados por la DIAN")
                .tag("outcome", outcome).register(registry);
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
