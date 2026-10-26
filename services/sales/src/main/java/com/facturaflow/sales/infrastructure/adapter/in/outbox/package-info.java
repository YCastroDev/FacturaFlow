/**
 * Adaptadores de entrada del outbox (ADR 0013): el relay programado que publica los eventos pendientes, el
 * componente informativo {@code messaging} del health y las métricas {@code sales.outbox.*}. Todos entran
 * por los puertos {@link com.facturaflow.sales.application.port.in.PublishPendingEventsUseCase} y
 * {@link com.facturaflow.sales.application.port.in.GetOutboxStatusUseCase}.
 */
package com.facturaflow.sales.infrastructure.adapter.in.outbox;
