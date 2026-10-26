package com.facturaflow.sales.application.port.out;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Un evento guardado en el outbox, listo para publicarse tal cual (ADR 0013). El caso de uso no
 * interpreta {@code payload}: es el mensaje completo (sobre + datos) que se serializó al guardar la venta.
 *
 * @param eventId    identidad del evento; los consumidores la usan para descartar duplicados
 * @param routingKey clave de enrutamiento en el exchange del servicio (p. ej. {@code sale.confirmed.v1})
 * @param payload    JSON del mensaje, tal como se publica
 */
public record OutboxMessage(UUID eventId, String eventType, int eventVersion, String routingKey, String payload,
                            Instant occurredAt) {

    public OutboxMessage {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(routingKey, "routingKey");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (eventVersion < 1) {
            throw new IllegalArgumentException("La versión de un evento empieza en 1: " + eventVersion);
        }
    }
}
