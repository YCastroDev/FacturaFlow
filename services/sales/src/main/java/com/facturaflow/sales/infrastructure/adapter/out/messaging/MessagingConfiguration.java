package com.facturaflow.sales.infrastructure.adapter.out.messaging;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Beans del adaptador de mensajería. El exchange se declara como bean: {@code RabbitAdmin} (de Spring
 * Boot) lo crea en el broker cada vez que abre una conexión, así que no importa si RabbitMQ estaba caído
 * al arrancar. Durable: sobrevive a un reinicio del broker.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MessagingProperties.class)
class MessagingConfiguration {

    /** Topic: los consumidores eligen qué eventos reciben con patrones de routing key (p. ej. {@code sale.#}). */
    @Bean
    TopicExchange salesEventsExchange(MessagingProperties properties) {
        return new TopicExchange(properties.exchange(), true, false);
    }

    @Bean
    RabbitEventPublisher rabbitEventPublisher(RabbitTemplate template, MessagingProperties properties) {
        return new RabbitEventPublisher(template, properties);
    }
}
