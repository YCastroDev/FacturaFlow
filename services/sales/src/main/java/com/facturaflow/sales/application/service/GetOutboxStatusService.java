package com.facturaflow.sales.application.service;

import com.facturaflow.sales.application.port.in.GetOutboxStatusUseCase;
import com.facturaflow.sales.application.port.out.OutboxRepository;
import com.facturaflow.sales.application.port.out.OutboxStatus;
import java.util.Objects;

public final class GetOutboxStatusService implements GetOutboxStatusUseCase {

    private final OutboxRepository outbox;

    public GetOutboxStatusService(OutboxRepository outbox) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
    }

    @Override
    public OutboxStatus status() {
        return outbox.status();
    }
}
