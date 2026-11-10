package com.facturaflow.catalog.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Copia deliberada en los cuatro servicios, como {@link ObservabilityConfiguration} (ADR 0019). */
class ObservabilityConfigurationTest {

    private final ObservationPredicate predicate = new ObservabilityConfiguration().actuatorRequestsAreNotObserved();

    private boolean observed(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        return predicate.test("http.server.requests",
                new ServerRequestObservationContext(request, new MockHttpServletResponse()));
    }

    @ParameterizedTest(name = "{0} → observada: {1}")
    @CsvSource({
            "/actuator,                      false",
            "/actuator/health,               false",
            "/actuator/health/liveness,      false",
            "/actuator/metrics/jvm.threads,  false",
            "/api/v1/products,               true",
            "/actuatorx,                     true",
            "/swagger-ui/index.html,         true"
    })
    @DisplayName("las peticiones a Actuator (healthchecks) no generan trazas; las de la API sí")
    void actuatorRequestsAreNotObserved(String path, boolean expected) {
        assertThat(observed(path)).isEqualTo(expected);
    }

    @Test
    @DisplayName("el filtro solo mira las peticiones entrantes: las demás observaciones siguen")
    void otherObservationsAreKept() {
        assertThat(predicate.test("http.client.requests", new Observation.Context())).isTrue();
    }
}
