package com.facturaflow.invoicing.application.port.out;

import com.facturaflow.invoicing.application.exception.CreditNoteConcurrentModificationException;
import com.facturaflow.invoicing.application.exception.DuplicateCreditNoteReferenceCodeException;
import com.facturaflow.invoicing.application.exception.InvoiceConcurrentModificationException;
import com.facturaflow.invoicing.domain.model.CreditNote;
import com.facturaflow.invoicing.domain.model.CreditNoteId;
import com.facturaflow.invoicing.domain.model.InvoiceId;
import com.facturaflow.invoicing.domain.model.ReferenceCode;
import java.util.List;
import java.util.Optional;

/**
 * Puerto de salida: guarda y recupera el agregado {@link CreditNote} completo (ADR 0009).
 *
 * <p>{@link #saveNew} es el punto que protege la regla que cruza agregados: la nota se validó
 * contra una foto de la factura y de sus notas, y solo se inserta si esa foto sigue vigente.
 */
public interface CreditNoteRepository {

    /**
     * Inserta una nota nueva y, en la MISMA transacción, sube la versión de su factura solo si
     * sigue siendo {@code invoiceVersionRead} (bloqueo optimista sobre la factura, ADR 0009). Toda
     * nota nueva pasa por aquí, así que dos creaciones simultáneas sobre la misma factura nunca
     * se validan contra la misma foto: la segunda falla y debe volver a leer y validar.
     *
     * @param invoiceVersionRead versión de la factura con la que se calculó el saldo
     * @throws InvoiceConcurrentModificationException si la factura cambió (p. ej. otra nota) desde esa lectura
     * @throws DuplicateCreditNoteReferenceCodeException si otra nota ya usa su código de referencia
     * @throws IllegalArgumentException si la nota ya tiene versión (use {@link #save})
     */
    CreditNote saveNew(CreditNote creditNote, RequestHash requestHash, long invoiceVersionRead);

    /**
     * Guarda los cambios de estado de una nota existente. No toca la factura: emitir o rechazar
     * una nota nunca aumenta lo acreditado.
     *
     * @throws CreditNoteConcurrentModificationException si la nota cambió desde que se leyó
     * @throws IllegalArgumentException si la nota nunca se ha guardado (use {@link #saveNew})
     */
    CreditNote save(CreditNote creditNote);

    Optional<CreditNote> findById(CreditNoteId id);

    /** Todas las notas de la factura, en cualquier estado, en orden de creación. */
    List<CreditNote> findByInvoiceId(InvoiceId invoiceId);

    /** Busca por código de referencia e incluye la huella de la petición que creó la nota. */
    Optional<StoredCreditNote> findStoredByReferenceCode(ReferenceCode referenceCode);
}
