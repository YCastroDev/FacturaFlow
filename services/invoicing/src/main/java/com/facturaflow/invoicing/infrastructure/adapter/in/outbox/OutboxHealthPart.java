package com.facturaflow.invoicing.infrastructure.adapter.in.outbox;

import com.facturaflow.invoicing.application.port.in.GetOutboxStatusUseCase;
import com.facturaflow.invoicing.application.port.out.OutboxStatus;
import com.facturaflow.invoicing.infrastructure.config.MessagingHealthPart;
import java.util.LinkedHashMap;
import java.util.Map;

/** Parte {@code outbox} del componente {@code messaging}: el estado del relay y los eventos pendientes (ADR 0015). */
final class OutboxHealthPart implements MessagingHealthPart {

    private final GetOutboxStatusUseCase outbox;
    private final RelayState state;

    OutboxHealthPart(GetOutboxStatusUseCase outbox, RelayState state) {
        this.outbox = outbox;
        this.state = state;
    }

    @Override
    public String name() {
        return "outbox";
    }

    @Override
    public Map<String, Object> details() {
        Map<String, Object> details = new LinkedHashMap<>(state.details());
        try {
            OutboxStatus status = outbox.status();
            details.put("pendingEvents", status.pending());
            status.oldestPendingSince().ifPresent(since -> details.put("oldestPendingSince", since.toString()));
        } catch (RuntimeException e) {
            // La base tiene su propio indicador (db); aquí solo se informa.
            details.put("pendingEvents", "desconocido (" + e.getClass().getSimpleName() + ")");
        }
        return details;
    }
}
