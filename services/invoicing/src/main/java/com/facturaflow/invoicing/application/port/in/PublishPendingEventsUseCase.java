package com.facturaflow.invoicing.application.port.in;

/**
 * Publica en el broker un lote de eventos pendientes del outbox (ADR 0015). Lo invoca periódicamente el
 * relay; varias instancias pueden invocarlo a la vez sin publicar dos veces el mismo evento.
 */
public interface PublishPendingEventsUseCase {

    PublishPendingEventsResult publishPending();
}
