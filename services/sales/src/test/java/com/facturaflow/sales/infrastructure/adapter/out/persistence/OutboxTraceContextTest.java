package com.facturaflow.sales.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * Las columnas de la traza en el outbox (ADR 0019). La captura con una traza real la prueban los tests de propagación
 * ({@code SaleTracingEndToEndTest}); aquí, los bordes.
 */
class OutboxTraceContextTest {

    private static final String TRACE_PARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Test
    @DisplayName("sin tracing configurado (ni Tracer ni Propagator), no se guarda contexto y el evento se guarda igual")
    void withoutTracingNothingIsCaptured() {
        StaticListableBeanFactory none = new StaticListableBeanFactory();
        OutboxTraceContext context = new OutboxTraceContext(none.getBeanProvider(Tracer.class),
                none.getBeanProvider(Propagator.class));

        assertThat(context.capture()).isEqualTo(OutboxTraceContext.Columns.NONE);
    }

    @Test
    @DisplayName("una fila sin traza (anterior a la migración) no aporta cabeceras: el mensaje sale con una traza nueva")
    void rowWithoutTraceHasNoHeaders() {
        assertThat(OutboxTraceContext.headers(null, null)).isEmpty();
    }

    @Test
    @DisplayName("las columnas vuelven como las cabeceras W3C traceparent y tracestate")
    void columnsBecomeW3cHeaders() {
        assertThat(OutboxTraceContext.headers(TRACE_PARENT, null)).isEqualTo(Map.of("traceparent", TRACE_PARENT));
        assertThat(OutboxTraceContext.headers(TRACE_PARENT, "vendor=valor"))
                .isEqualTo(Map.of("traceparent", TRACE_PARENT, "tracestate", "vendor=valor"));
    }
}
