package com.facturaflow.invoicing.application.service;

import com.facturaflow.invoicing.application.exception.CreditNoteConcurrentModificationException;
import com.facturaflow.invoicing.application.exception.DuplicateCreditNoteReferenceCodeException;
import com.facturaflow.invoicing.application.exception.InvoiceConcurrentModificationException;
import com.facturaflow.invoicing.application.port.out.CreditNoteRepository;
import com.facturaflow.invoicing.application.port.out.RequestHash;
import com.facturaflow.invoicing.application.port.out.StoredCreditNote;
import com.facturaflow.invoicing.domain.model.CreditNote;
import com.facturaflow.invoicing.domain.model.CreditNoteId;
import com.facturaflow.invoicing.domain.model.InvoiceId;
import com.facturaflow.invoicing.domain.model.ReferenceCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Repositorio de notas en memoria que respeta el contrato del puerto, como
 * {@link InMemoryInvoiceRepository}: UNIQUE por código de referencia, bloqueo optimista de la nota
 * y, al insertar, el de la FACTURA (sube su versión solo si sigue siendo la leída). Guarda copias.
 *
 * <p>{@link #beforeEachSaveNew} simula a otra petición actuando justo antes de cada inserción
 * (p. ej. otra nota sobre la misma factura), para reproducir carreras de forma determinista.
 */
final class InMemoryCreditNoteRepository implements CreditNoteRepository {

    private record Row(CreditNote creditNote, RequestHash requestHash) {
    }

    private final InMemoryInvoiceRepository invoices;
    private final Map<CreditNoteId, Row> rows = new LinkedHashMap<>();
    private Runnable beforeNextSaveNew = () -> { };
    private Runnable beforeEachSaveNew = () -> { };
    private Runnable beforeNextSave = () -> { };
    private int saveNewAttempts;

    InMemoryCreditNoteRepository(InMemoryInvoiceRepository invoices) {
        this.invoices = invoices;
    }

    void beforeNextSaveNew(Runnable action) {
        this.beforeNextSaveNew = action;
    }

    /** Como {@link #beforeNextSaveNew}, pero en TODAS las inserciones (p. ej. un competidor incansable). */
    void beforeEachSaveNew(Runnable action) {
        this.beforeEachSaveNew = action;
    }

    void beforeNextSave(Runnable action) {
        this.beforeNextSave = action;
    }

    int saveNewAttempts() {
        return saveNewAttempts;
    }

    int size() {
        return rows.size();
    }

    /** Inserta sin pasar por las reglas del puerto: datos de partida de un test. */
    CreditNote insert(CreditNote creditNote) {
        rows.put(creditNote.id(), new Row(copy(creditNote, 0L), new RequestHash("f".repeat(64))));
        return copy(creditNote, 0L);
    }

    @Override
    public CreditNote saveNew(CreditNote creditNote, RequestHash requestHash, long invoiceVersionRead) {
        saveNewAttempts++;
        Runnable competitor = beforeNextSaveNew;
        beforeNextSaveNew = () -> { };   // se limpia antes: la acción puede usar el repositorio
        competitor.run();
        beforeEachSaveNew.run();
        if (creditNote.version().isPresent()) {
            throw new IllegalArgumentException("ya guardada");
        }
        boolean duplicate = rows.values().stream()
                .anyMatch(row -> row.creditNote().referenceCode().equals(creditNote.referenceCode()));
        if (duplicate) {
            throw new DuplicateCreditNoteReferenceCodeException(creditNote.referenceCode().value(), null);
        }
        if (!invoices.incrementVersion(creditNote.invoiceId(), invoiceVersionRead)) {
            throw new InvoiceConcurrentModificationException(creditNote.invoiceId().value(), null);
        }
        CreditNote stored = copy(creditNote, 0L);
        rows.put(creditNote.id(), new Row(stored, requestHash));
        return copy(stored, 0L);
    }

    @Override
    public CreditNote save(CreditNote creditNote) {
        Runnable competitor = beforeNextSave;
        beforeNextSave = () -> { };
        competitor.run();
        long readVersion = creditNote.version().orElseThrow(() -> new IllegalArgumentException("nunca guardada"));
        Row current = rows.get(creditNote.id());
        if (current == null || current.creditNote().version().orElseThrow() != readVersion) {
            throw new CreditNoteConcurrentModificationException(creditNote.id().value(), null);
        }
        CreditNote stored = copy(creditNote, readVersion + 1);
        rows.put(creditNote.id(), new Row(stored, current.requestHash()));
        return copy(stored, readVersion + 1);
    }

    @Override
    public Optional<CreditNote> findById(CreditNoteId id) {
        return Optional.ofNullable(rows.get(id)).map(row -> current(row.creditNote()));
    }

    @Override
    public List<CreditNote> findByInvoiceId(InvoiceId invoiceId) {
        return rows.values().stream()
                .filter(row -> row.creditNote().invoiceId().equals(invoiceId))
                .map(row -> current(row.creditNote()))
                .toList();
    }

    @Override
    public Optional<StoredCreditNote> findStoredByReferenceCode(ReferenceCode referenceCode) {
        return rows.values().stream()
                .filter(row -> row.creditNote().referenceCode().equals(referenceCode))
                .findFirst()
                .map(row -> new StoredCreditNote(current(row.creditNote()), row.requestHash()));
    }

    private static CreditNote current(CreditNote stored) {
        return copy(stored, stored.version().orElseThrow());
    }

    private static CreditNote copy(CreditNote note, long version) {
        return CreditNote.reconstitute(note.id(), note.referenceCode(), note.invoiceId(), note.reason(), note.lines(),
                note.issueDate(), note.observation().orElse(null), note.status(),
                note.electronicDocument().orElse(null), note.rejectionReason().orElse(null), version);
    }
}
