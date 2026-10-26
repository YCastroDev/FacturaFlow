package com.facturaflow.sales.infrastructure.adapter.in.messaging;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuración del consumo de los resultados de facturación ({@code sales.invoice-results.*}, ADR 0015). Mismo
 * significado que en el consumidor de invoicing (ADR 0014).
 *
 * @param queue              cola de sales para los resultados (la declara sales)
 * @param sourceExchange     exchange de invoicing; sales solo enlaza su cola, no lo declara
 * @param routingKeys        las versiones de los eventos que se consumen
 * @param retryExchange      exchange de las colas de espera
 * @param requeueExchange    exchange por el que el broker devuelve a la cola un mensaje cuya espera venció
 * @param deadLetterExchange exchange de la DLQ
 * @param retryDelays        espera antes de cada reintento; su número es el de reintentos
 * @param confirmTimeout     cuánto se espera la confirmación del broker al republicar
 * @param concurrency        consumidores simultáneos
 */
@Validated
@ConfigurationProperties(prefix = "sales.invoice-results")
record ConsumerProperties(
        @NotBlank String queue,
        @NotBlank String sourceExchange,
        @NotEmpty List<@NotBlank String> routingKeys,
        @NotBlank String retryExchange,
        @NotBlank String requeueExchange,
        @NotBlank String deadLetterExchange,
        @NotEmpty List<@NotNull Duration> retryDelays,
        @NotNull Duration confirmTimeout,
        @Min(1) @Max(10) int concurrency) {

    String deadLetterQueue() {
        return queue + ".dlq";
    }

    /** La cola de espera de un retraso; el nombre lleva la duración (cambiarla crea otra cola). */
    String waitQueue(Duration delay) {
        return queue + ".wait-" + label(delay);
    }

    static String label(Duration delay) {
        long millis = delay.toMillis();
        if (millis % 60_000 == 0) {
            return millis / 60_000 + "m";
        }
        if (millis % 1_000 == 0) {
            return millis / 1_000 + "s";
        }
        return millis + "ms";
    }
}
