package com.facturaflow.sales.infrastructure.adapter.out.catalog;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Beans del adaptador de catalog. Vive dentro de su paquete para que nada de catalog sea visible fuera
 * (regla de ArchUnit). Las instancias "catalog" de Resilience4j se crean en los registros del starter, así
 * sus métricas y endpoints de Actuator las ven.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CatalogProperties.class)
class CatalogConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(CatalogConfiguration.class);

    @Bean
    CatalogResilience catalogResilience(CatalogProperties properties,
                                        ObjectProvider<CircuitBreakerRegistry> circuitBreakers,
                                        ObjectProvider<RetryRegistry> retries) {
        return new CatalogResilience(properties, circuitBreakers.getIfAvailable(CircuitBreakerRegistry::ofDefaults),
                retries.getIfAvailable(RetryRegistry::ofDefaults));
    }

    @Bean
    CatalogHealthIndicator catalogHealthIndicator(CatalogResilience resilience) {
        return new CatalogHealthIndicator(resilience);
    }

    @Bean
    CatalogTokenProvider catalogTokenProvider(CatalogProperties properties, Clock clock) {
        return new CatalogTokenProvider(properties.auth(), clock);
    }

    @Bean
    CatalogRestClient catalogRestClient(CatalogProperties properties, CatalogResilience resilience,
                                        CatalogTokenProvider tokens) {
        LOG.info("Catalog: {} (connect {}, read {}); token de {} en {}", properties.baseUrl(),
                properties.connectTimeout(), properties.readTimeout(), properties.auth().clientId(),
                properties.auth().tokenUri());
        return new CatalogRestClient(new CatalogHttp(properties, resilience, tokens));
    }
}
