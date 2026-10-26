package com.facturaflow.sales.infrastructure.adapter.out.catalog;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Componente {@code catalog} de {@code /actuator/health} (ADR 0012): INFORMATIVO, como el de Factus en
 * invoicing. Siempre UP, con el estado del circuito en los detalles: catalog caído no significa que sales
 * esté mal (sigue consultando ventas y respondiendo reintentos), así que nunca baja el health agregado ni
 * entra en liveness/readiness, que reiniciarían la instancia sin arreglar nada.
 */
final class CatalogHealthIndicator implements HealthIndicator {

    private final CatalogResilience resilience;

    CatalogHealthIndicator(CatalogResilience resilience) {
        this.resilience = resilience;
    }

    @Override
    public Health health() {
        return Health.up().withDetails(resilience.snapshot()).build();
    }
}
