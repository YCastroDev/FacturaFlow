package com.facturaflow.invoicing.application.service;

import com.facturaflow.invoicing.application.exception.DuplicateReferenceCodeException;
import com.facturaflow.invoicing.application.exception.InvoiceConcurrentModificationException;
import com.facturaflow.invoicing.application.port.out.InvoiceRepository;
import com.facturaflow.invoicing.application.port.out.RequestHash;
import com.facturaflow.invoicing.application.port.out.StoredInvoice;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.domain.model.InvoiceId;
import com.facturaflow.invoicing.domain.model.ReferenceCode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Repositorio en memoria que respeta el contrato del puerto: UNIQUE por código de referencia,
 * versión que sube en cada {@code save} y bloqueo optimista. Guarda COPIAS, como una base real:
 * modificar la instancia que se tiene en mano no cambia lo guardado hasta llamar a {@code save}.
 *
 * <p>Los "hooks" permiten simular a otra petición actuando justo antes de una escritura, para
 * reproducir carreras de forma determinista.
 */
final class InMemoryInvoiceRepository implements InvoiceRepository {

    private record Row(Invoice invoice, RequestHash requestHash) {
    }

    private final Map<InvoiceId, Row> rows = new LinkedHashMap<>();
    private Runnable beforeNextSaveNew = () -> { };
    private Runnable beforeNextSave = () -> { };
    private int saveCount;

    /** Ejecuta {@code action} justo antes del próximo {@code saveNew} (p. ej. otra petición que inserta). */
    void beforeNextSaveNew(Runnable action) {
        this.beforeNextSaveNew = action;
    }

    /** Ejecuta {@code action} justo antes del próximo {@code save} (p. ej. otra emisión que gana la carrera). */
    void beforeNextSave(Runnable action) {
        this.beforeNextSave = action;
    }

    int size() {
        return rows.size();
    }

    /** Veces que se llamó a {@code save} (actualización), con o sin éxito. */
    int saveCount() {
        return saveCount;
    }

    @Override
    public Invoice saveNew(Invoice invoice, RequestHash requestHash) {
        Runnable competitor = beforeNextSaveNew;
        beforeNextSaveNew = () -> { };   // se limpia antes: la acción puede usar el repositorio
        competitor.run();
        if (invoice.version().isPresent()) {
            throw new IllegalArgumentException("ya guardada");
        }
        boolean duplicate = rows.values().stream()
                .anyMatch(row -> row.invoice().referenceCode().equals(invoice.referenceCode()));
        if (duplicate) {
            throw new DuplicateReferenceCodeException(invoice.referenceCode().value(), null);
        }
        Invoice stored = copy(invoice, 0L);
        rows.put(invoice.id(), new Row(stored, requestHash));
        return copy(stored, 0L);
    }

    @Override
    public Invoice save(Invoice invoice) {
        saveCount++;
        Runnable competitor = beforeNextSave;
        beforeNextSave = () -> { };
        competitor.run();
        long readVersion = invoice.version().orElseThrow(() -> new IllegalArgumentException("nunca guardada"));
        Row current = rows.get(invoice.id());
        if (current == null || current.invoice().version().orElseThrow() != readVersion) {
            throw new InvoiceConcurrentModificationException(invoice.id().value(), null);
        }
        Invoice stored = copy(invoice, readVersion + 1);
        rows.put(invoice.id(), new Row(stored, current.requestHash()));
        return copy(stored, readVersion + 1);
    }

    @Override
    public Optional<Invoice> findById(InvoiceId id) {
        return Optional.ofNullable(rows.get(id)).map(row -> copy(row.invoice(), row.invoice().version().orElseThrow()));
    }

    @Override
    public Optional<Invoice> findByReferenceCode(ReferenceCode referenceCode) {
        return findStoredByReferenceCode(referenceCode).map(StoredInvoice::invoice);
    }

    @Override
    public Optional<StoredInvoice> findStoredByReferenceCode(ReferenceCode referenceCode) {
        return rows.values().stream()
                .filter(row -> row.invoice().referenceCode().equals(referenceCode))
                .findFirst()
                .map(row -> new StoredInvoice(copy(row.invoice(), row.invoice().version().orElseThrow()),
                        row.requestHash()));
    }

    private static Invoice copy(Invoice invoice, long version) {
        return Invoice.reconstitute(invoice.id(), invoice.referenceCode(), invoice.customer(), invoice.lines(),
                invoice.paymentMethod(), invoice.paymentTerms(), invoice.issueDate(),
                invoice.observation().orElse(null), invoice.status(), invoice.electronicDocument().orElse(null),
                invoice.rejectionReason().orElse(null), version);
    }
}
