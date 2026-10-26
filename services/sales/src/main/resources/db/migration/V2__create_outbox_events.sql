-- V2: outbox transaccional de eventos (ADR 0013). Cada evento se inserta en la MISMA transacción que la
-- venta que lo produjo; el relay lo publica en RabbitMQ después y lo marca solo cuando el broker confirma.

CREATE TABLE outbox_events (
    -- Es el eventId del mensaje: los consumidores descartan duplicados con él.
    id               UUID          PRIMARY KEY,
    aggregate_type   TEXT          NOT NULL,
    aggregate_id     UUID          NOT NULL,
    event_type       TEXT          NOT NULL,
    event_version    INTEGER       NOT NULL,
    routing_key      TEXT          NOT NULL,
    -- El mensaje completo (sobre + datos) tal como se publica. TEXT y no JSONB: JSONB reordena las claves
    -- y el mensaje debe salir byte a byte igual en cada reintento; IS JSON valida que sea un objeto JSON.
    payload          TEXT          NOT NULL,
    occurred_at      TIMESTAMPTZ   NOT NULL,
    -- NULL = pendiente. Se llena solo tras la confirmación (publisher confirm) de RabbitMQ.
    published_at     TIMESTAMPTZ,
    -- Intentos fallidos y el último motivo (sin datos del mensaje): para diagnosticar desde la base.
    attempts         INTEGER       NOT NULL DEFAULT 0,
    last_error       TEXT,
    CONSTRAINT ck_outbox_events_payload CHECK (payload IS JSON OBJECT),
    -- Cada tipo de evento nuevo exige una migración (como los enums persistidos).
    CONSTRAINT ck_outbox_events_aggregate_type CHECK (aggregate_type IN ('Sale')),
    CONSTRAINT ck_outbox_events_event_type CHECK (event_type IN ('SaleConfirmed')),
    CONSTRAINT ck_outbox_events_event_version CHECK (event_version >= 1),
    CONSTRAINT ck_outbox_events_attempts CHECK (attempts >= 0)
);

-- El relay y la métrica solo miran los pendientes, en orden de ocurrencia: índice parcial pequeño, que no
-- crece con el histórico de eventos ya publicados.
CREATE INDEX ix_outbox_events_pending ON outbox_events (occurred_at, id) WHERE published_at IS NULL;
