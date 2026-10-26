package com.facturaflow.sales.application.service;

import com.facturaflow.sales.application.port.out.OutboxBatch;
import com.facturaflow.sales.application.port.out.OutboxMessage;
import com.facturaflow.sales.application.port.out.OutboxRepository;
import com.facturaflow.sales.application.port.out.OutboxStatus;
import com.facturaflow.sales.application.port.out.PublicationResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

/** Outbox en memoria con el mismo contrato que el adaptador JPA (sin bloqueos: un solo hilo). */
class InMemoryOutboxRepository implements OutboxRepository {

    /** Un evento guardado y su estado de publicación. */
    static final class Row {
        final OutboxMessage message;
        boolean published;
        int attempts;
        String lastError;

        Row(OutboxMessage message) {
            this.message = message;
        }
    }

    private final List<Row> rows = new ArrayList<>();

    void add(OutboxMessage message) {
        rows.add(new Row(message));
    }

    Row row(OutboxMessage message) {
        return rows.stream().filter(row -> row.message.eventId().equals(message.eventId())).findFirst().orElseThrow();
    }

    @Override
    public OutboxBatch publishPending(int batchSize, Function<List<OutboxMessage>, PublicationResult> publisher) {
        List<Row> batch = pending().limit(batchSize).toList();
        if (batch.isEmpty()) {
            return OutboxBatch.empty();
        }
        List<OutboxMessage> messages = batch.stream().map(row -> row.message).toList();
        PublicationResult result = publisher.apply(messages);
        for (Row row : batch) {
            if (result.confirmed().contains(row.message.eventId())) {
                row.published = true;
            } else {
                row.attempts++;
                row.lastError = result.failure().orElse("Sin confirmación del broker");
            }
        }
        return new OutboxBatch(messages, result);
    }

    @Override
    public OutboxStatus status() {
        return new OutboxStatus(pending().count(), pending().findFirst().map(row -> row.message.occurredAt()));
    }

    private Stream<Row> pending() {
        return rows.stream().filter(row -> !row.published)
                .sorted(Comparator.comparing((Row row) -> row.message.occurredAt()));
    }
}
