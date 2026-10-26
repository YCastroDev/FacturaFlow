package com.facturaflow.sales.infrastructure.adapter.in.messaging;

import com.facturaflow.sales.application.port.in.RecordInvoiceResultUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Beans del consumidor de resultados de facturación (ADR 0015). Vive en su paquete: nada de él es visible fuera. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ConsumerProperties.class)
class ConsumerConfiguration {

    @Bean
    Declarables invoiceResultTopology(ConsumerProperties properties) {
        return InvoiceResultTopology.declarables(properties);
    }

    @Bean
    ConsumerState invoiceResultConsumerState(MeterRegistry registry, Clock clock) {
        return new ConsumerState(registry, clock);
    }

    @Bean
    InvoiceResultListener invoiceResultListener(RecordInvoiceResultUseCase recordResult, RabbitTemplate template,
                                                ConsumerProperties properties, ConsumerState state, Clock clock) {
        return new InvoiceResultListener(new InvoiceResultProcessor(new InvoiceResultTranslator(), recordResult),
                new ConfirmedRepublisher(template, properties.confirmTimeout()), properties, state, clock);
    }

    @Bean
    ConsumerHealthPart consumerHealthPart(RabbitListenerEndpointRegistry listeners, ConsumerProperties properties,
                                          ConsumerState state) {
        return new ConsumerHealthPart(listeners, properties, state);
    }
}
