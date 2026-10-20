-- V1: línea base del esquema de invoicing.
-- Intencionalmente sin tablas: el modelo de facturas aún no está definido.
-- Esta migración verifica que Flyway se conecta con invoicing_user, crea
-- flyway_schema_history y deja el esquema versionado desde el día uno.
-- Las tablas reales llegarán en V2__..., cuando se diseñe el dominio.
SELECT 1;
