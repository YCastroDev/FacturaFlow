package com.facturaflow.sales.infrastructure.adapter.out.catalog;

import com.facturaflow.sales.application.exception.CatalogUnavailableException;
import com.facturaflow.sales.application.exception.CatalogUnavailableException.Reason;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resiliencia de TODAS las llamadas a catalog (ADR 0012), programática y en un solo punto, con el mismo
 * enfoque que la de Factus en invoicing (ADR 0010): <b>Retry → CircuitBreaker → HTTP</b>, así cada
 * intento real cuenta en el circuito. Sin limitador: catalog es un servicio propio.
 *
 * <table>
 *   <tr><th>Resultado de un intento</th><th>Reintento</th><th>Falla del circuito</th></tr>
 *   <tr><td>sin conexión (rechazada, host desconocido, timeout de CONEXIÓN), 502, 503, 504</td><td>sí, 1 vez</td><td>sí</td></tr>
 *   <tr><td>timeout de LECTURA, otro 5xx</td><td>no: ya gastó el tiempo, o no es pasajero</td><td>sí</td></tr>
 *   <tr><td>token de sales-service (ADR 0018): Keycloak sin conexión o 502/503/504; 401 de catalog (se descarta el
 *       token)</td><td>sí, 1 vez</td><td>sí</td></tr>
 *   <tr><td>token: Keycloak rechaza las credenciales; 403 de catalog (configuración)</td><td>no</td><td>sí</td></tr>
 *   <tr><td>2xx, 404 y otros 4xx</td><td>no: la respuesta sube tal cual</td><td>no</td></tr>
 * </table>
 * Agotados los intentos, o con el circuito abierto, {@link CatalogUnavailableException} (503 con
 * {@code Retry-After}): la venta no se crea.
 */
final class CatalogResilience {

    static final String NAME = "catalog";

    private static final Logger LOG = LoggerFactory.getLogger(CatalogResilience.class);

    private final Retry retry;
    private final CircuitBreaker circuitBreaker;
    private final Duration waitInOpenState;
    private final Duration failureRetryAfter;
    /** {@link System#nanoTime()} del último paso a OPEN; 0 si nunca se abrió. */
    private final AtomicLong openedAtNanos = new AtomicLong();

    CatalogResilience(CatalogProperties properties, CircuitBreakerRegistry circuitBreakers, RetryRegistry retries) {
        CatalogProperties.Resilience settings = properties.resilience();
        this.waitInOpenState = settings.circuitBreaker().waitDurationInOpenState();
        this.failureRetryAfter = properties.failureRetryAfter();
        this.retry = retries.retry(NAME, retryConfig(settings.retry()));
        this.circuitBreaker = circuitBreakers.circuitBreaker(NAME, circuitBreakerConfig(settings.circuitBreaker()));
        circuitBreaker.getEventPublisher().onStateTransition(event -> {
            CircuitBreaker.StateTransition transition = event.getStateTransition();
            if (transition.getToState() == CircuitBreaker.State.OPEN) {
                openedAtNanos.set(System.nanoTime());
                LOG.warn("Circuito de catalog: {} → {}. Crear ventas responderá 503 durante {} s; luego se probará "
                        + "de nuevo.", transition.getFromState(), transition.getToState(), waitInOpenState.toSeconds());
            } else {
                LOG.warn("Circuito de catalog: {} → {}.", transition.getFromState(), transition.getToState());
            }
        });
    }

    /** Ejecuta {@code call} (un intento HTTP) con reintento y circuit breaker. */
    CatalogResponse call(String description, Supplier<CatalogResponse> call) {
        Supplier<CatalogResponse> classified = () -> {
            CatalogResponse response = call.get();
            if (response.status() >= 500) {
                throw new CatalogTechnicalException(description + ": catalog respondió HTTP " + response.status(),
                        isRetryableStatus(response.status()), null);
            }
            return response;
        };
        Supplier<CatalogResponse> decorated = Retry.decorateSupplier(retry,
                CircuitBreaker.decorateSupplier(circuitBreaker, classified));
        try {
            return decorated.get();
        } catch (CatalogTechnicalException failed) {
            throw new CatalogUnavailableException(failed.getMessage(), Reason.FAILURE, failureRetryAfter, failed);
        } catch (CallNotPermittedException open) {
            Duration retryAfter = remainingOpenTime();
            throw new CatalogUnavailableException(description + ": circuito de catalog abierto tras fallas repetidas; "
                    + "se volverá a probar en " + retryAfter.toSeconds() + " s", Reason.CIRCUIT_OPEN, retryAfter, open);
        }
    }

    /** Estado para el health indicator: nada sensible, solo contadores y tiempos. */
    Map<String, Object> snapshot() {
        CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("circuitBreaker", circuitBreaker.getState().name());
        details.put("failureRate", metrics.getFailureRate());   // -1 mientras no haya llamadas suficientes
        details.put("bufferedCalls", metrics.getNumberOfBufferedCalls());
        details.put("failedCalls", metrics.getNumberOfFailedCalls());
        details.put("notPermittedCalls", metrics.getNumberOfNotPermittedCalls());
        if (circuitBreaker.getState() == CircuitBreaker.State.OPEN) {
            details.put("retryAfterSeconds", remainingOpenTime().toSeconds());
        }
        return details;
    }

    CircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }

    /** Pasajeros: el proxy o el contenedor de catalog está reiniciando. */
    static boolean isRetryableStatus(int status) {
        return status == 502 || status == 503 || status == 504;
    }

    private static RetryConfig retryConfig(CatalogProperties.RetrySettings settings) {
        return RetryConfig.custom()
                .maxAttempts(settings.maxAttempts())
                .intervalFunction(IntervalFunction.ofRandomized(settings.backoff(), settings.jitter()))
                .retryOnException(error -> error instanceof CatalogTechnicalException failed && failed.retryable())
                .build();
    }

    private static CircuitBreakerConfig circuitBreakerConfig(CatalogProperties.CircuitBreakerSettings settings) {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(settings.slidingWindowSize())
                .minimumNumberOfCalls(settings.minimumNumberOfCalls())
                .failureRateThreshold(settings.failureRateThreshold())
                .waitDurationInOpenState(settings.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(settings.permittedNumberOfCallsInHalfOpenState())
                // Solo las fallas técnicas cuentan; un 404 (cliente o producto inexistente) es una respuesta normal.
                .recordException(error -> error instanceof CatalogTechnicalException)
                .build();
    }

    /** Lo que falta para que el circuito deje pasar una llamada de prueba; al menos 1 s. */
    private Duration remainingOpenTime() {
        long openedAt = openedAtNanos.get();
        Duration elapsed = openedAt == 0 ? Duration.ZERO : Duration.ofNanos(System.nanoTime() - openedAt);
        return wholeSecondsRoundedUp(waitInOpenState.minus(elapsed));
    }

    /** Segundos enteros para {@code Retry-After}: redondeo hacia arriba y nunca menos de 1 s (ni 0 ni negativo). */
    static Duration wholeSecondsRoundedUp(Duration remaining) {
        return Duration.ofSeconds(Math.max(1, (remaining.toMillis() + 999) / 1000));
    }
}
