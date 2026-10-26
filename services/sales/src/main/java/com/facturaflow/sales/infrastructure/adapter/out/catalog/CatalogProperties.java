package com.facturaflow.sales.infrastructure.adapter.out.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuración del cliente de catalog ({@code sales.catalog.*}). Valores y su porqué en
 * {@code application.yml} y en el ADR 0012. No hay credenciales: catalog es interno.
 *
 * @param failureRetryAfter {@code Retry-After} del 503 cuando la llamada falló (con el circuito
 *                          abierto se usa lo que falta para que vuelva a probar)
 */
@Validated
@ConfigurationProperties(prefix = "sales.catalog")
record CatalogProperties(
        @NotNull URI baseUrl,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @NotNull Duration failureRetryAfter,
        @NotNull @Valid Resilience resilience) {

    record Resilience(@NotNull @Valid RetrySettings retry, @NotNull @Valid CircuitBreakerSettings circuitBreaker) {
    }

    /**
     * @param maxAttempts intentos en total (el primero incluido)
     * @param backoff     espera antes de reintentar
     * @param jitter      fracción aleatoria de la espera (0,5 = ±50 %)
     */
    record RetrySettings(@Min(1) @Max(5) int maxAttempts, @NotNull Duration backoff,
                         @DecimalMin("0.0") @DecimalMax("1.0") double jitter) {
    }

    record CircuitBreakerSettings(@Min(1) int slidingWindowSize, @Min(1) int minimumNumberOfCalls,
                                  @DecimalMin("1") @DecimalMax("100") float failureRateThreshold,
                                  @NotNull Duration waitDurationInOpenState,
                                  @Min(1) int permittedNumberOfCallsInHalfOpenState) {
    }
}
