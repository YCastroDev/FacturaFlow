package com.facturaflow.sales.application.port.out;

import com.facturaflow.sales.domain.model.ReferenceCode;
import com.facturaflow.sales.domain.model.Sale;
import com.facturaflow.sales.domain.model.SaleId;
import com.facturaflow.sales.domain.model.SaleStatus;
import java.util.Optional;

/** Puerto de salida: guarda y recupera el agregado {@link Sale} completo (líneas e impuestos incluidos). */
public interface SaleRepository {

    /**
     * Inserta una venta que nunca se ha guardado, de forma atómica, con la huella de su petición y sus
     * eventos pendientes ({@link Sale#pendingEvents()}) en el outbox: o se guardan la venta y sus eventos,
     * o nada (ADR 0013). Devuelve la venta tal como quedó persistida, con su versión inicial y sin eventos
     * pendientes.
     *
     * @throws com.facturaflow.sales.application.exception.DuplicateReferenceCodeException si otra venta ya
     *         usa su código de referencia
     */
    Sale saveNew(Sale sale, RequestHash requestHash);

    Optional<Sale> findById(SaleId id);

    Optional<Sale> findByReferenceCode(ReferenceCode referenceCode);

    /** Como {@link #findByReferenceCode}, pero incluye la huella de la petición que creó la venta. */
    Optional<StoredSale> findStoredByReferenceCode(ReferenceCode referenceCode);

    /** De la más reciente a la más antigua; {@code status} nulo = todas. */
    SalePage findPage(SaleStatus status, int page, int size);
}
