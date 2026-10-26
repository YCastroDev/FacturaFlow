/**
 * Adaptadores de entrada del outbox (ADR 0015): el relay programado que publica los eventos pendientes, el
 * parte {@code outbox} del componente informativo {@code messaging} del health y las métricas {@code invoicing.outbox.*}. Todos entran
 * por los puertos {@link com.facturaflow.invoicing.application.port.in.PublishPendingEventsUseCase} y
 * {@link com.facturaflow.invoicing.application.port.in.GetOutboxStatusUseCase}.
 */
package com.facturaflow.invoicing.infrastructure.adapter.in.outbox;
