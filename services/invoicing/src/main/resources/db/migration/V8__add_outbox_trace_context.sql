-- V8: contexto de la traza en el outbox (ADR 0019). Al guardar el evento, en la MISMA transacción, se copia el contexto
-- W3C de la traza en curso (cabeceras traceparent y tracestate); el relay lo reanuda al publicar, para que el
-- consumidor continúe la misma traza aunque el mensaje salga segundos (o horas) después y desde otro hilo.
--
-- Columnas opcionales: NULL en los eventos guardados antes de este cambio o sin una traza activa (el mensaje sale
-- entonces con una traza nueva). TEXT sin formato fijo: el valor lo escribe y lo lee el propagador de
-- OpenTelemetry, y la especificación W3C admite versiones futuras con más campos.

ALTER TABLE outbox_events ADD COLUMN trace_parent TEXT;
ALTER TABLE outbox_events ADD COLUMN trace_state TEXT;

-- tracestate solo tiene sentido junto con un traceparent.
ALTER TABLE outbox_events ADD CONSTRAINT ck_outbox_events_trace_state CHECK (trace_state IS NULL OR trace_parent IS NOT NULL);
