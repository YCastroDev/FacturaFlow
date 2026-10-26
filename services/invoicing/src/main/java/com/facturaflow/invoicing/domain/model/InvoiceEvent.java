package com.facturaflow.invoicing.domain.model;

import com.facturaflow.invoicing.domain.exception.InvalidInvoiceException;
import java.util.UUID;

/**
 * Hecho de facturación que otros servicios deben conocer (ADR 0015). El dominio solo dice QUÉ pasó; cómo se
 * guarda (outbox), se serializa y se publica lo decide infraestructura. Sin instante: el dominio no lee el reloj y
 * las transiciones no reciben uno; el instante del hecho lo pone el adaptador de persistencia al guardarlo, en la
 * misma transacción. Al ser {@code sealed}, cada evento nuevo obliga a decidir su mensaje en el adaptador.
 */
public sealed interface InvoiceEvent {

    /** La factura quedó ISSUED. El mensaje sale del agregado (número, CUFE, total oficial). */
    record InvoiceIssued(InvoiceId invoiceId) implements InvoiceEvent {

        public InvoiceIssued {
            if (invoiceId == null) {
                throw InvalidInvoiceException.missing("el id de la factura del evento");
            }
        }
    }

    /** La factura quedó REJECTED (la DIAN no la validó). El mensaje lleva el motivo del agregado. */
    record InvoiceRejected(InvoiceId invoiceId) implements InvoiceEvent {

        public InvoiceRejected {
            if (invoiceId == null) {
                throw InvalidInvoiceException.missing("el id de la factura del evento");
            }
        }
    }

    /**
     * Una petición de factura no se pudo convertir en factura (datos que el dominio rechaza, código de referencia
     * ya usado con otro contenido, petición fuera del contrato): no hay factura ni la habrá con esos datos. No es
     * de un agregado (la factura no existe); lo registra {@code RejectInvoiceRequestUseCase}.
     *
     * @param sourceId      identificador de la petición en su origen (p. ej. la venta), si se conoce; trazabilidad
     * @param sourceEventId evento que pidió la factura, si se conoce; trazabilidad
     */
    record InvoiceRequestRejected(String referenceCode, UUID sourceId, UUID sourceEventId, String reason)
            implements InvoiceEvent {

        public InvoiceRequestRejected {
            if (referenceCode == null || referenceCode.isBlank()) {
                throw InvalidInvoiceException.missing("el código de referencia de la petición rechazada");
            }
            if (reason == null || reason.isBlank()) {
                throw InvalidInvoiceException.missing("el motivo del rechazo de la petición");
            }
        }
    }
}
