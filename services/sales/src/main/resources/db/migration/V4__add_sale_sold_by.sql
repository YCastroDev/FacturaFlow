-- V4: quién hizo la venta (ADR 0018, decisión 13): copia del usuario del token al vender. "sub" (identificador
-- estable) y "preferred_username" (para mostrar), con el tope de 255 caracteres del dominio (el de Keycloak).
-- Las ventas creadas antes de este cambio no tienen el dato: quedan en NULL, sin valores ficticios. Las columnas son de
-- solo inserción, como el resto del snapshot.

ALTER TABLE sales ADD COLUMN sold_by_user_id VARCHAR(255);
ALTER TABLE sales ADD COLUMN sold_by_username VARCHAR(255);

ALTER TABLE sales ADD CONSTRAINT ck_sales_sold_by CHECK ((sold_by_user_id IS NULL) = (sold_by_username IS NULL));
