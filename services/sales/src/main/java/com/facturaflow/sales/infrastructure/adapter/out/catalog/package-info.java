/**
 * Anti-Corruption Layer hacia catalog (ADR 0012), todo package-private: los DTOs y el cliente HTTP de
 * catalog no salen de este paquete (regla de ArchUnit); el resto del servicio usa el puerto
 * {@link com.facturaflow.sales.application.port.out.CatalogClient}.
 */
package com.facturaflow.sales.infrastructure.adapter.out.catalog;
