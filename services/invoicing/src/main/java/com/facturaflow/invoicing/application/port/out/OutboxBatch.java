package com.facturaflow.invoicing.application.port.out;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Un lote del outbox ya procesado: los mensajes que se tomaron y lo que el broker confirmó de ellos. */
public record OutboxBatch(List<OutboxMessage> messages, PublicationResult result) {

    public OutboxBatch {
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        Objects.requireNonNull(result, "result");
    }

    public static OutboxBatch empty() {
        return new OutboxBatch(List.of(), PublicationResult.allConfirmed(Set.of()));
    }
}
