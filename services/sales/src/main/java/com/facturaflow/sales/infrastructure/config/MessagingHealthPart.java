package com.facturaflow.sales.infrastructure.config;

import java.util.Map;

/**
 * Una parte del componente informativo {@code messaging} del health (ADR 0015): el productor (outbox y relay) o
 * el consumidor. Cada adaptador aporta la suya sin exponer sus clases (que siguen package-private); el componente
 * las reúne en {@link MessagingHealthIndicator}.
 */
public interface MessagingHealthPart {

    /** Clave de la parte en los detalles ({@code outbox}, {@code consumer}). */
    String name();

    /** Detalles informativos; nunca lanza ni contiene secretos. */
    Map<String, Object> details();
}
