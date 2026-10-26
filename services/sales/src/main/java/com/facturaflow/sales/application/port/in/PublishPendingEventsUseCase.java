package com.facturaflow.sales.application.port.in;

/**
 * Publica en el broker un lote de eventos pendientes del outbox (ADR 0013). Lo invoca periódicamente el
 * relay; varias instancias pueden invocarlo a la vez sin publicar dos veces el mismo evento.
 */
public interface PublishPendingEventsUseCase {

    PublishPendingEventsResult publishPending();
}
