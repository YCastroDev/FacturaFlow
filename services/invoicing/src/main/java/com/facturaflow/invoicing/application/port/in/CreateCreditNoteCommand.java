package com.facturaflow.invoicing.application.port.in;

import com.facturaflow.invoicing.domain.model.CreditNoteReason;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Datos para crear una nota crédito, con tipos simples: el caso de uso construye los value
 * objects (ADR 0006). {@code lines} va vacía en una anulación total; {@code null} equivale a vacía.
 * El cliente no viaja aquí: es siempre el de la factura guardada (ADR 0009).
 */
public record CreateCreditNoteCommand(UUID invoiceId, String referenceCode, CreditNoteReason reason,
                                      List<Line> lines, String observation) {

    public CreateCreditNoteCommand {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** Línea de la factura (número desde 1) y cantidad a acreditar. */
    public record Line(int lineNumber, BigDecimal quantity) {
    }
}
