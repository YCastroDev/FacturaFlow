package com.facturaflow.sales.domain.model;

import com.facturaflow.sales.domain.exception.InvalidSaleException;
import java.time.Instant;

/**
 * Hecho de negocio que registró el agregado {@link Sale} y que otros servicios deben conocer (ADR 0013).
 * El dominio solo dice QUÉ pasó; cómo se guarda (outbox), se serializa y se publica lo decide
 * infraestructura. Al ser {@code sealed}, un {@code switch} sobre los eventos es exhaustivo: un evento nuevo
 * obliga a decidir su mensaje en el adaptador.
 */
public sealed interface SaleEvent {

    SaleId saleId();

    /** Cuándo ocurrió el hecho (no cuándo se publica). */
    Instant occurredAt();

    /**
     * La venta se confirmó. El mensaje sale completo del agregado (snapshot de catalog incluido), sin
     * volver a consultar a catalog (ADR 0012).
     */
    record SaleConfirmed(SaleId saleId, Instant occurredAt) implements SaleEvent {

        public SaleConfirmed {
            if (saleId == null) {
                throw InvalidSaleException.missing("el id de la venta del evento");
            }
            if (occurredAt == null) {
                throw InvalidSaleException.missing("el instante del evento");
            }
        }
    }
}
