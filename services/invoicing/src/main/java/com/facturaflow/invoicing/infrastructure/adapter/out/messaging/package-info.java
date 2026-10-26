/**
 * Publicación de los eventos de facturación en {@code invoicing.events} (ADR 0015), todo package-private: nada
 * de este paquete sale de él (regla 12 de ArchUnit); el resto del servicio usa el puerto
 * {@link com.facturaflow.invoicing.application.port.out.EventPublisher}.
 */
package com.facturaflow.invoicing.infrastructure.adapter.out.messaging;
