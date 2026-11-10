package com.facturaflow.sales.infrastructure.adapter.out.persistence;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * El contexto de la traza en el outbox (ADR 0019, decisión 2). Al guardar un evento se copia el contexto W3C de la
 * traza en curso ({@code traceparent} y {@code tracestate}) en su fila, en la MISMA transacción; al leerlo, vuelve
 * como el mapa opaco {@link com.facturaflow.sales.application.port.out.OutboxMessage#traceContext()} que el
 * publicador reanuda. Así el consumidor continúa la traza de la petición que creó la venta.
 *
 * <p>Usa el propagador de Micrometer (el mismo que pone {@code traceparent} en HTTP y en RabbitMQ), no un formato
 * propio. Sin tracing (o sin una traza activa) no guarda nada: el evento sale con una traza nueva.
 */
@Component
class OutboxTraceContext {

    static final String TRACEPARENT = "traceparent";
    static final String TRACESTATE = "tracestate";

    /** Columnas de la fila: {@code traceParent} nulo si no había traza; {@code traceState} nulo si venía vacío. */
    record Columns(String traceParent, String traceState) {

        static final Columns NONE = new Columns(null, null);
    }

    private final Tracer tracer;
    private final Propagator propagator;

    OutboxTraceContext(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this.tracer = tracer.getIfAvailable(() -> Tracer.NOOP);
        this.propagator = propagator.getIfAvailable(() -> Propagator.NOOP);
    }

    /** El contexto de la traza en curso, listo para guardarse junto con el evento. */
    Columns capture() {
        Span span = tracer.currentSpan();
        if (span == null) {
            return Columns.NONE;
        }
        Map<String, String> headers = new HashMap<>();
        propagator.inject(span.context(), headers, Map::put);
        String traceParent = headers.get(TRACEPARENT);
        if (traceParent == null || traceParent.isBlank()) {
            return Columns.NONE;
        }
        String traceState = headers.get(TRACESTATE);
        return new Columns(traceParent, traceState == null || traceState.isBlank() ? null : traceState);
    }

    /** Las columnas de una fila como cabeceras de propagación (vacío si el evento no tenía traza). */
    static Map<String, String> headers(String traceParent, String traceState) {
        if (traceParent == null) {
            return Map.of();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(TRACEPARENT, traceParent);
        if (traceState != null) {
            headers.put(TRACESTATE, traceState);
        }
        return headers;
    }
}
