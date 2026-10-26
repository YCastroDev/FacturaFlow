package com.facturaflow.invoicing.application.port.out;

import com.facturaflow.invoicing.application.exception.DuplicateReferenceCodeException;
import com.facturaflow.invoicing.application.exception.InvoiceConcurrentModificationException;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.domain.model.InvoiceId;
import com.facturaflow.invoicing.domain.model.ReferenceCode;
import java.util.Optional;

/**
 * Puerto de salida: guarda y recupera el agregado {@link Invoice} completo (líneas e impuestos incluidos).
 * Crear y actualizar son operaciones distintas: una factura nueva siempre se guarda con la
 * huella de la petición que la originó (idempotencia, ADR 0006).
 */
public interface InvoiceRepository {

    /**
     * Inserta una factura que nunca se ha guardado (versión vacía) de forma atómica.
     * Devuelve la factura tal como quedó persistida, con su versión inicial.
     *
     * @throws DuplicateReferenceCodeException si otra factura ya usa su código de referencia
     * @throws IllegalArgumentException si la factura ya tiene versión (use {@link #save})
     */
    Invoice saveNew(Invoice invoice, RequestHash requestHash);

    /**
     * Guarda los cambios de una factura ya existente de forma atómica, junto con sus eventos pendientes
     * ({@link Invoice#pendingEvents()}) en el outbox: o se guardan el cambio de estado y sus eventos, o nada
     * (ADR 0015). Devuelve la factura con su nueva versión y sin eventos pendientes: quien llama debe seguir
     * trabajando con la instancia devuelta.
     *
     * @throws InvoiceConcurrentModificationException si la factura cambió desde que se leyó
     * @throws IllegalArgumentException si la factura nunca se ha guardado (use {@link #saveNew})
     */
    Invoice save(Invoice invoice);

    Optional<Invoice> findById(InvoiceId id);

    Optional<Invoice> findByReferenceCode(ReferenceCode referenceCode);

    /** Como {@link #findByReferenceCode}, pero incluye la huella de la petición que creó la factura. */
    Optional<StoredInvoice> findStoredByReferenceCode(ReferenceCode referenceCode);
}
