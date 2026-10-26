package com.facturaflow.invoicing.application.port.in;

import com.facturaflow.invoicing.application.port.out.OutboxStatus;

/** Consulta cuántos eventos esperan en el outbox y desde cuándo (health informativo y métricas, ADR 0015). */
public interface GetOutboxStatusUseCase {

    OutboxStatus status();
}
