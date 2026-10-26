package com.facturaflow.sales.infrastructure.adapter.in.outbox;

import com.facturaflow.sales.application.port.in.GetOutboxStatusUseCase;
import com.facturaflow.sales.application.port.in.PublishPendingEventsUseCase;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Beans de los adaptadores de entrada del outbox. */
@Configuration(proxyBeanMethods = false)
class OutboxConfiguration {

    @Bean
    RelayState relayState() {
        return new RelayState();
    }

    @Bean
    OutboxHealthPart outboxHealthPart(GetOutboxStatusUseCase outbox, RelayState state) {
        return new OutboxHealthPart(outbox, state);
    }

    @Bean
    OutboxMetrics outboxMetrics(GetOutboxStatusUseCase outbox, Clock clock) {
        return new OutboxMetrics(outbox, clock);
    }

    /**
     * El relay programado. {@code sales.outbox.relay.enabled=false} lo apaga: lo usan los tests que
     * inspeccionan el outbox y lo publican a mano, para que un ciclo en segundo plano no interfiera.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnBooleanProperty(name = "sales.outbox.relay.enabled", matchIfMissing = true)
    static class RelaySchedulingConfiguration {

        @Bean
        OutboxRelayScheduler outboxRelayScheduler(PublishPendingEventsUseCase useCase, RelayState state, Clock clock) {
            return new OutboxRelayScheduler(useCase, state, clock);
        }
    }
}
