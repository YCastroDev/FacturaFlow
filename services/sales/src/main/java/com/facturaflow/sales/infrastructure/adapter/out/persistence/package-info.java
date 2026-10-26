/**
 * Adaptador de persistencia (JPA). Entidades package-private, separadas del dominio, con mapeo a mano en
 * {@link com.facturaflow.sales.infrastructure.adapter.out.persistence.SalePersistenceMapper}. Único lugar
 * del servicio con transacciones (regla de ArchUnit, como en invoicing).
 */
package com.facturaflow.sales.infrastructure.adapter.out.persistence;
