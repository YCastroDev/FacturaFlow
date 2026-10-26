/**
 * Publicación de eventos en RabbitMQ (ADR 0013), todo package-private: nada de Spring AMQP sale de este
 * paquete (regla 11 de ArchUnit); el resto del servicio usa el puerto
 * {@link com.facturaflow.sales.application.port.out.EventPublisher}.
 */
package com.facturaflow.sales.infrastructure.adapter.out.messaging;
