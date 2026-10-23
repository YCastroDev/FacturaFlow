package com.facturaflow.invoicing.application.service;

import com.facturaflow.invoicing.application.port.out.RequestHash;
import com.facturaflow.invoicing.domain.model.CreditNoteReason;
import com.facturaflow.invoicing.domain.model.CreditedQuantity;
import com.facturaflow.invoicing.domain.model.InvoiceId;
import java.util.Comparator;
import java.util.List;

/**
 * Huella de una petición de creación de nota crédito (ADR 0009), con el mismo criterio que la de
 * facturas (ADR 0006, D1): dos peticiones son iguales si producirían la misma nota, salvo el id y
 * la fecha. Entran la factura, el motivo, las líneas (número y cantidad, ya normalizadas por el
 * dominio) y la observación normalizada; queda fuera el código de referencia, que es la clave.
 *
 * <p>Las líneas se ordenan por número: la nota las guarda en ese orden, así que el orden de la
 * petición no significa nada.
 */
final class CreditNoteRequestHasher {

    static final String FORMAT_VERSION = "credit-note-request-v1";

    private CreditNoteRequestHasher() {
    }

    static RequestHash hash(InvoiceId invoiceId, CreditNoteReason reason, List<CreditedQuantity> lines,
                            String normalizedObservation) {
        return CanonicalText.sha256(canonicalForm(invoiceId, reason, lines, normalizedObservation));
    }

    static String canonicalForm(InvoiceId invoiceId, CreditNoteReason reason, List<CreditedQuantity> lines,
                                String normalizedObservation) {
        CanonicalText out = new CanonicalText();
        out.add(FORMAT_VERSION);
        out.add(invoiceId.value().toString());
        out.add(reason.name());
        List<CreditedQuantity> sorted = lines.stream()
                .sorted(Comparator.comparingInt(CreditedQuantity::invoiceLineNumber)
                        .thenComparing(line -> line.quantity().value()))
                .toList();
        out.add(Integer.toString(sorted.size()));
        for (CreditedQuantity line : sorted) {
            out.add(Integer.toString(line.invoiceLineNumber()));
            out.add(line.quantity().value().toPlainString());
        }
        out.add(normalizedObservation);
        return out.toString();
    }
}
