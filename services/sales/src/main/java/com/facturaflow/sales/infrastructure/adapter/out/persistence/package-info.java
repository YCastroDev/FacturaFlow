/**
 * Adaptador de persistencia (JPA). Entidades package-private, separadas del dominio, con mapeo a mano en
 * {@link com.facturaflow.sales.infrastructure.adapter.out.persistence.SalePersistenceMapper}. Único lugar
 * del servicio con transacciones (regla de ArchUnit, como en invoicing). Incluye el outbox de eventos
 * (ADR 0013): la venta y sus eventos se guardan en la misma transacción, y
 * {@link com.facturaflow.sales.infrastructure.adapter.out.persistence.SaleEventMessages} fija el contrato de
 * los mensajes.
 */
package com.facturaflow.sales.infrastructure.adapter.out.persistence;
