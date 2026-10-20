package com.facturaflow.invoicing.application.port.out;

import com.facturaflow.invoicing.application.exception.DuplicateReferenceCodeException;
import com.facturaflow.invoicing.application.exception.InvoiceConcurrentModificationException;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.domain.model.InvoiceId;
import com.facturaflow.invoicing.domain.model.ReferenceCode;
import java.util.Optional;

/** Puerto de salida: guarda y recupera el agregado {@link Invoice} completo (líneas e impuestos incluidos). */
public interface InvoiceRepository {

    /**
     * Guarda la factura de forma atómica. Devuelve la factura tal como quedó persistida,
     * con su nueva versión: quien llama debe seguir trabajando con la instancia devuelta.
     *
     * @throws DuplicateReferenceCodeException si otra factura ya usa su código de referencia
     * @throws InvoiceConcurrentModificationException si la factura cambió desde que se leyó
     */
    Invoice save(Invoice invoice);

    Optional<Invoice> findById(InvoiceId id);

    Optional<Invoice> findByReferenceCode(ReferenceCode referenceCode);
}
