package com.facturaflow.sales.domain.model;

import com.facturaflow.sales.domain.exception.InvalidInvoicingOutcomeException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Cómo terminó la facturación de la venta (ADR 0015), según los eventos de invoicing. Sellado: un {@code switch}
 * sobre él es exhaustivo. Las fechas son las del hecho en invoicing ({@code occurredAt} del evento): el dominio no
 * lee el reloj.
 */
public sealed interface InvoicingOutcome {

    Instant at();

    /** Factura emitida: la venta queda INVOICED. */
    record Invoiced(InvoiceReference invoice, Instant at) implements InvoicingOutcome {

        public Invoiced {
            if (invoice == null) {
                throw InvalidInvoicingOutcomeException.missing("la factura emitida");
            }
            if (at == null) {
                throw InvalidInvoicingOutcomeException.missing("el instante de la emisión");
            }
        }
    }

    /**
     * La venta no se pudo facturar: la DIAN rechazó la factura o invoicing rechazó la petición. La venta queda
     * INVOICE_FAILED.
     *
     * @param invoiceId la factura rechazada, si llegó a existir (vacío si se rechazó la petición)
     */
    record Failed(String reason, UUID invoiceId, Instant at) implements InvoicingOutcome {

        public static final int MAX_REASON_LENGTH = 1000;

        public Failed {
            if (reason == null || reason.isBlank()) {
                throw InvalidInvoicingOutcomeException.missing("el motivo del fallo");
            }
            reason = reason.strip();
            if (reason.length() > MAX_REASON_LENGTH) {
                throw InvalidInvoicingOutcomeException.tooLong("motivo del fallo", MAX_REASON_LENGTH);
            }
            if (at == null) {
                throw InvalidInvoicingOutcomeException.missing("el instante del fallo");
            }
        }

        public Optional<UUID> rejectedInvoiceId() {
            return Optional.ofNullable(invoiceId);
        }
    }
}
