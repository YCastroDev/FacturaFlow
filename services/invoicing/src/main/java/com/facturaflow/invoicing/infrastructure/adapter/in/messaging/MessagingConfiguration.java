package com.facturaflow.invoicing.infrastructure.adapter.in.messaging;

import com.facturaflow.invoicing.application.port.in.CreateInvoiceUseCase;
import com.facturaflow.invoicing.application.port.in.IssueInvoiceUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Beans del consumidor de eventos (ADR 0014). Vive en su paquete para que nada de él sea visible fuera. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MessagingProperties.class)
class MessagingConfiguration {

    @Bean
    Declarables saleConfirmedTopology(MessagingProperties properties) {
        return SaleConfirmedTopology.declarables(properties);
    }

    @Bean
    ConsumerState saleConfirmedConsumerState(MeterRegistry registry, Clock clock) {
        return new ConsumerState(registry, clock);
    }

    @Bean
    SaleConfirmedListener saleConfirmedListener(CreateInvoiceUseCase createInvoice, IssueInvoiceUseCase issueInvoice,
                                                RabbitTemplate template, MessagingProperties properties,
                                                ConsumerState state, Clock clock) {
        SaleConfirmedProcessor processor = new SaleConfirmedProcessor(new SaleConfirmedTranslator(),
                new SaleTotalsCheck(), createInvoice, issueInvoice);
        return new SaleConfirmedListener(processor, new ConfirmedRepublisher(template, properties.confirmTimeout()),
                properties, state, clock);
    }

    @Bean
    MessagingHealthIndicator messagingHealthIndicator(RabbitListenerEndpointRegistry listeners,
                                                      MessagingProperties properties, ConsumerState state) {
        return new MessagingHealthIndicator(listeners, properties, state);
    }
}
