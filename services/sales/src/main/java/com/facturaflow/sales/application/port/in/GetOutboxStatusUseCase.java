package com.facturaflow.sales.application.port.in;

import com.facturaflow.sales.application.port.out.OutboxStatus;

/** Consulta cuántos eventos esperan en el outbox y desde cuándo (health informativo y métricas, ADR 0013). */
public interface GetOutboxStatusUseCase {

    OutboxStatus status();
}
