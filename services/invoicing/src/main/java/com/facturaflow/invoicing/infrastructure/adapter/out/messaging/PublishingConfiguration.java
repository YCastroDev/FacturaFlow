package com.facturaflow.invoicing.infrastructure.adapter.out.messaging;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Beans del adaptador de mensajería. El exchange se declara como bean: {@code RabbitAdmin} (de Spring
 * Boot) lo crea en el broker cada vez que abre una conexión, así que no importa si RabbitMQ estaba caído
 * al arrancar. Durable: sobrevive a un reinicio del broker.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PublishingProperties.class)
class PublishingConfiguration {

    /** Topic: los consumidores eligen qué eventos reciben con patrones de routing key (p. ej. {@code sale.#}). */
    @Bean
    TopicExchange invoicingEventsExchange(PublishingProperties properties) {
        return new TopicExchange(properties.exchange(), true, false);
    }

    @Bean
    RabbitEventPublisher rabbitEventPublisher(RabbitTemplate template, PublishingProperties properties,
                                              ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        // Sin tracing configurado (p. ej. un test que lo apaga), los NOOP: se publica igual, sin trazas.
        return new RabbitEventPublisher(template, properties, tracer.getIfAvailable(() -> Tracer.NOOP),
                propagator.getIfAvailable(() -> Propagator.NOOP));
    }
}
