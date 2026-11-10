package com.facturaflow.catalog.config;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Trazas sin ruido (ADR 0019, D6). Con muestreo al 100 % en local, cada healthcheck del compose (cada 5–10 s) sería
 * una traza: las peticiones a Actuator no se observan (ni span ni métrica {@code http.server.requests}). Los spans
 * internos de Spring Security y las ejecuciones de {@code @Scheduled} se apagan con
 * {@code management.observations.enable.*} en {@code application.yml}.
 *
 * <p>Copia deliberada en los cuatro servicios: si cambia una, cambian las cuatro (con su test).
 */
@Configuration(proxyBeanMethods = false)
class ObservabilityConfiguration {

    static final String ACTUATOR_PATH = "/actuator";

    @Bean
    ObservationPredicate actuatorRequestsAreNotObserved() {
        return (name, context) -> !(context instanceof ServerRequestObservationContext request
                && isActuator(request.getCarrier().getRequestURI()));
    }

    static boolean isActuator(String path) {
        return path != null && (path.equals(ACTUATOR_PATH) || path.startsWith(ACTUATOR_PATH + "/"));
    }
}
