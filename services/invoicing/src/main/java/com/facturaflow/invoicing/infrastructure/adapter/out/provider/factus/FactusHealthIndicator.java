package com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Componente {@code factus} de {@code /actuator/health} (ADR 0010): INFORMATIVO. Siempre reporta UP y
 * muestra en los detalles el estado del circuito y del limitador. Que Factus esté caído no significa que
 * este servicio esté mal: puede seguir creando y consultando documentos. Por eso nunca baja el health
 * agregado ni entra en las sondas {@code liveness}/{@code readiness}, que reiniciarían o sacarían de
 * servicio la instancia sin arreglar nada.
 */
final class FactusHealthIndicator implements HealthIndicator {

    private final FactusResilience resilience;

    FactusHealthIndicator(FactusResilience resilience) {
        this.resilience = resilience;
    }

    @Override
    public Health health() {
        return Health.up().withDetails(resilience.snapshot()).build();
    }
}
