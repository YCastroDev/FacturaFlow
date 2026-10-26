-- V7: outbox transaccional de eventos de facturación (ADR 0015; mismo diseño que el de sales, ADR 0013). Los
-- eventos de una factura se insertan en la MISMA transacción que su cambio de estado; el relay los publica en
-- invoicing.events y los marca solo cuando RabbitMQ confirma.

CREATE TABLE outbox_events (
    -- Es el eventId del mensaje: los consumidores descartan duplicados con él.
    id               UUID          PRIMARY KEY,
    aggregate_type   TEXT          NOT NULL,
    -- La factura del evento; NULL en una petición rechazada (no hay factura).
    aggregate_id     UUID,
    event_type       TEXT          NOT NULL,
    event_version    INTEGER       NOT NULL,
    routing_key      TEXT          NOT NULL,
    -- El mensaje completo (sobre + datos) tal como se publica. TEXT y no JSONB: JSONB reordena las claves y el
    -- mensaje debe salir igual en cada reintento; IS JSON valida que sea un objeto JSON.
    payload          TEXT          NOT NULL,
    occurred_at      TIMESTAMPTZ   NOT NULL,
    -- NULL = pendiente. Se llena solo tras la confirmación (publisher confirm) de RabbitMQ.
    published_at     TIMESTAMPTZ,
    attempts         INTEGER       NOT NULL DEFAULT 0,
    last_error       TEXT,
    CONSTRAINT ck_outbox_events_payload CHECK (payload IS JSON OBJECT),
    -- Cada tipo de evento nuevo exige una migración (como los enums persistidos).
    CONSTRAINT ck_outbox_events_type CHECK (
        (aggregate_type = 'Invoice' AND aggregate_id IS NOT NULL
            AND event_type IN ('InvoiceIssued', 'InvoiceRejected'))
        OR (aggregate_type = 'InvoiceRequest' AND aggregate_id IS NULL
            AND event_type = 'InvoiceRequestRejected')),
    CONSTRAINT ck_outbox_events_event_version CHECK (event_version >= 1),
    CONSTRAINT ck_outbox_events_attempts CHECK (attempts >= 0)
);

-- El relay y la métrica solo miran los pendientes, en orden de ocurrencia.
CREATE INDEX ix_outbox_events_pending ON outbox_events (occurred_at, id) WHERE published_at IS NULL;
