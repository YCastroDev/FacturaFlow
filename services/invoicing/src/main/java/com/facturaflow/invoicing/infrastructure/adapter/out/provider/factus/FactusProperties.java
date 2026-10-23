package com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuración del adaptador de Factus ({@code invoicing.factus.*}). Las credenciales llegan solo
 * por variables de entorno ({@code FACTUS_*}); si falta alguna, la aplicación no arranca con
 * {@code invoicing.provider=factus}. {@link #toString()} nunca muestra las credenciales.
 *
 * @param numberingRangeId opcional: sin él, Factus usa el rango de facturación por defecto
 * @param creditNoteNumberingRangeId obligatorio: la cuenta tiene varios rangos de nota crédito y
 *        Factus exige elegir uno (spike 0002, sorpresa 4)
 * @param tokenRefreshMargin el token se renueva este tiempo antes de que venza
 * @param resilience reintentos, circuit breaker y limitador de las llamadas a Factus (ADR 0010)
 */
@Validated
@ConfigurationProperties(prefix = "invoicing.factus")
record FactusProperties(
        @NotNull URI baseUrl,
        @NotBlank String clientId,
        @NotBlank String clientSecret,
        @NotBlank String username,
        @NotBlank String password,
        boolean sendEmail,
        @Positive Integer numberingRangeId,
        @NotNull @Positive Integer creditNoteNumberingRangeId,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @NotNull Duration tokenRefreshMargin,
        @NotBlank String userAgent,
        @NotNull @Valid Resilience resilience) {

    /** Valores y su porqué en el ADR 0010 y en {@code application.yml}. */
    record Resilience(@NotNull @Valid RetrySettings retry, @NotNull @Valid CircuitBreakerSettings circuitBreaker,
                      @NotNull @Valid RateLimiterSettings rateLimiter) {
    }

    /**
     * @param maxAttempts   intentos en total (el primero incluido)
     * @param jitter        fracción aleatoria de cada espera (0,5 = ±50 %)
     * @param maxRetryAfter un 429 se reintenta solo si su {@code Retry-After} no supera esto
     */
    record RetrySettings(@Min(1) @Max(10) int maxAttempts, @NotNull Duration initialWait,
                         @DecimalMin("1.0") double multiplier, @DecimalMin("0.0") @DecimalMax("1.0") double jitter,
                         @NotNull Duration maxRetryAfter) {
    }

    record CircuitBreakerSettings(@Min(1) int slidingWindowSize, @Min(1) int minimumNumberOfCalls,
                                  @DecimalMin("1") @DecimalMax("100") float failureRateThreshold,
                                  @NotNull Duration waitDurationInOpenState,
                                  @Min(1) int permittedNumberOfCallsInHalfOpenState) {
    }

    /** {@code limitForPeriod} peticiones cada {@code limitRefreshPeriod}; esperar un permiso como máximo {@code timeout}. */
    record RateLimiterSettings(@Min(1) int limitForPeriod, @NotNull Duration limitRefreshPeriod,
                               @NotNull Duration timeout) {
    }

    @Override
    public String toString() {
        return "FactusProperties[baseUrl=" + baseUrl + ", clientId=***, clientSecret=***, username=***, "
                + "password=***, sendEmail=" + sendEmail + ", numberingRangeId=" + numberingRangeId
                + ", creditNoteNumberingRangeId=" + creditNoteNumberingRangeId
                + ", connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout
                + ", tokenRefreshMargin=" + tokenRefreshMargin + ", userAgent=" + userAgent
                + ", resilience=" + resilience + "]";
    }
}
