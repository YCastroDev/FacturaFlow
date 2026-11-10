package com.facturaflow.invoicing.application.port.out;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Un evento guardado en el outbox, listo para publicarse tal cual (ADR 0015, mismo patrón que sales en el ADR
 * 0013). El caso de uso no interpreta {@code payload}: es el mensaje completo (sobre + datos) que se serializó al
 * guardar el evento.
 *
 * @param eventId    identidad del evento; los consumidores la usan para descartar duplicados
 * @param routingKey clave de enrutamiento en el exchange del servicio (p. ej. {@code invoice.issued.v1})
 * @param payload    JSON del mensaje, tal como se publica
 * @param traceContext cabeceras de propagación de la traza en la que se guardó el evento ({@code traceparent},
 *                     {@code tracestate}; ADR 0019). Opaco, como {@code payload}: la aplicación no lo interpreta, solo
 *                     lo lleva del outbox al publicador. Vacío si el evento se guardó sin una traza activa.
 */
public record OutboxMessage(UUID eventId, String eventType, int eventVersion, String routingKey, String payload,
                            Instant occurredAt, Map<String, String> traceContext) {

    /** Un evento sin contexto de traza. */
    public OutboxMessage(UUID eventId, String eventType, int eventVersion, String routingKey, String payload,
                         Instant occurredAt) {
        this(eventId, eventType, eventVersion, routingKey, payload, occurredAt, Map.of());
    }

    public OutboxMessage {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(routingKey, "routingKey");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(occurredAt, "occurredAt");
        traceContext = Map.copyOf(Objects.requireNonNull(traceContext, "traceContext"));
        if (eventVersion < 1) {
            throw new IllegalArgumentException("La versión de un evento empieza en 1: " + eventVersion);
        }
    }
}
