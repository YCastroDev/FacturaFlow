package com.facturaflow.invoicing.infrastructure.adapter.in.messaging;

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
 * Configuración del consumo de {@code SaleConfirmed} ({@code invoicing.messaging.*}, ADR 0014). La conexión
 * (host, usuario, contraseña, vhost) es la estándar de Spring ({@code spring.rabbitmq.*}), de variables de
 * entorno. Valores y su porqué en {@code application.yml}.
 *
 * @param queue              cola de invoicing para SaleConfirmed (la declara invoicing)
 * @param sourceExchange     exchange del productor (sales); invoicing solo enlaza su cola, no lo declara
 * @param routingKey         routing key de la versión del evento que se consume
 * @param retryExchange      exchange de las colas de espera
 * @param requeueExchange    exchange por el que el broker devuelve a la cola un mensaje cuya espera venció
 * @param deadLetterExchange exchange de la DLQ
 * @param retryDelays        espera antes de cada reintento; su número es el de reintentos
 * @param confirmTimeout     cuánto se espera la confirmación del broker al republicar en espera o en la DLQ
 * @param concurrency        consumidores simultáneos (1: ver ADR 0014)
 */
@Validated
@ConfigurationProperties(prefix = "invoicing.messaging")
record MessagingProperties(
        @NotBlank String queue,
        @NotBlank String sourceExchange,
        @NotBlank String routingKey,
        @NotBlank String retryExchange,
        @NotBlank String requeueExchange,
        @NotBlank String deadLetterExchange,
        @NotEmpty List<@NotNull Duration> retryDelays,
        @NotNull Duration confirmTimeout,
        @Min(1) @Max(10) int concurrency) {

    /** La DLQ de la cola. */
    String deadLetterQueue() {
        return queue + ".dlq";
    }

    /**
     * La cola de espera de un retraso. El nombre lleva la duración ({@code .wait-10s}, {@code .wait-1m}): cambiar
     * un retraso crea otra cola en vez de chocar con el TTL de la existente (RabbitMQ no deja redeclarar una cola
     * con otros argumentos: PRECONDITION_FAILED).
     */
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
