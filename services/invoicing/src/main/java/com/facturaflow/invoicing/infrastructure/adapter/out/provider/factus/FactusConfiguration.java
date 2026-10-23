package com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Activa el adaptador de Factus con {@code invoicing.provider=factus} (ADR 0008). Solo entonces se
 * enlazan y validan las propiedades {@code invoicing.factus.*}: con el proveedor falso no se
 * necesitan credenciales. Vive dentro del paquete del adaptador para que nada de Factus sea visible
 * fuera de él (regla de ArchUnit).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "invoicing.provider", havingValue = "factus")
@EnableConfigurationProperties(FactusProperties.class)
class FactusConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(FactusConfiguration.class);

    /**
     * Reintentos, circuit breaker y limitador (ADR 0010). Las instancias "factus" se crean en los registros
     * del starter de Resilience4j, así sus métricas y sus endpoints de Actuator las ven; sin el starter
     * (tests de configuración) se usan registros propios.
     */
    @Bean
    FactusResilience factusResilience(FactusProperties properties,
                                      ObjectProvider<CircuitBreakerRegistry> circuitBreakers,
                                      ObjectProvider<RetryRegistry> retries,
                                      ObjectProvider<RateLimiterRegistry> rateLimiters,
                                      ObjectProvider<MeterRegistry> meters) {
        return new FactusResilience(properties.resilience(),
                circuitBreakers.getIfAvailable(CircuitBreakerRegistry::ofDefaults),
                retries.getIfAvailable(RetryRegistry::ofDefaults),
                rateLimiters.getIfAvailable(RateLimiterRegistry::ofDefaults),
                meters.getIfAvailable(SimpleMeterRegistry::new));
    }

    /** Componente informativo {@code factus} del health: siempre UP, con el estado del circuito. */
    @Bean
    FactusHealthIndicator factusHealthIndicator(FactusResilience resilience) {
        return new FactusHealthIndicator(resilience);
    }

    /** Un solo bean para los dos puertos (facturas y notas crédito): comparten cliente HTTP y token. */
    @Bean
    FactusElectronicInvoiceProvider factusElectronicInvoiceProvider(FactusProperties properties, Clock clock,
                                                                    FactusResilience resilience) {
        LOG.info("Proveedor de facturación electrónica: Factus ({}), send_email={}, numbering_range_id={}, "
                        + "numbering_range_id de notas crédito={}.", properties.baseUrl(), properties.sendEmail(),
                properties.numberingRangeId() == null ? "por defecto" : properties.numberingRangeId(),
                properties.creditNoteNumberingRangeId());
        return FactusElectronicInvoiceProvider.create(properties, clock, resilience);
    }
}
