package com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus;

import com.facturaflow.invoicing.application.exception.ElectronicInvoiceProviderUnavailableException.Reason;
import com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusProperties.CircuitBreakerSettings;
import com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusProperties.RateLimiterSettings;
import com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusProperties.RetrySettings;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resiliencia de TODAS las llamadas HTTP a Factus (ADR 0010): facturas, notas crédito, PDF y token.
 * Envuelve cada llamada, de afuera hacia adentro, en <b>Retry → CircuitBreaker → RateLimiter</b>:
 * cada intento real cuenta en el circuito y consume un permiso del limitador, y con el circuito
 * abierto no se gasta ningún permiso.
 *
 * <p><b>Qué se reintenta y qué cuenta como falla</b> (la regla vive aquí, en código):
 * <table>
 *   <tr><th>Resultado de la llamada</th><th>Reintento</th><th>Falla del circuito</th></tr>
 *   <tr><td>timeout, error de red, 5xx</td><td>sí</td><td>sí</td></tr>
 *   <tr><td>429</td><td>sí, si su Retry-After es razonable</td><td>sí</td></tr>
 *   <tr><td>409 "pendiente" (estado de UN documento, no una caída)</td><td>sí</td><td>no (se ignora)</td></tr>
 *   <tr><td>2xx, 401, 422, otros 4xx</td><td>no: la respuesta sube tal cual</td><td>no</td></tr>
 * </table>
 * El 401 lo sigue resolviendo {@link FactusClient} (renovar el token y un solo reintento) y la
 * verificación de identidad ocurre después, en el intérprete: ninguno de los dos pasa por aquí como
 * falla. Si los reintentos se agotan con una respuesta HTTP, esa última respuesta sube al intérprete,
 * que produce el mismo mensaje técnico de siempre.
 *
 * <p>Circuito abierto o limitador agotado: {@link FactusTechnicalException} con el motivo y el
 * {@code Retry-After} que verá el cliente.
 */
final class FactusResilience {

    static final String NAME = "factus";
    static final String REJECTED_METRIC = "invoicing.factus.rejected";
    private static final String REJECTED_DESCRIPTION =
            "Llamadas a Factus que no se hicieron: circuito abierto (circuit_open) o limitador agotado (rate_limited)";

    private static final Logger LOG = LoggerFactory.getLogger(FactusResilience.class);

    /** Una respuesta HTTP que merece otro intento; si se agotan, se devuelve tal cual. */
    private static final class RetryableResponse extends RuntimeException {

        private final transient FactusResponse response;

        RetryableResponse(FactusResponse response) {
            super("HTTP " + response.status(), null, false, false);
            this.response = response;
        }
    }

    private final Retry retry;
    private final CircuitBreaker circuitBreaker;
    private final RateLimiter rateLimiter;
    private final Duration waitInOpenState;
    private final Duration limitRefreshPeriod;
    private final Counter rejectedByCircuit;
    private final Counter rejectedByRateLimiter;
    /** {@link System#nanoTime()} del último paso a OPEN; 0 si nunca se abrió. */
    private final AtomicLong openedAtNanos = new AtomicLong();

    FactusResilience(FactusProperties.Resilience settings, CircuitBreakerRegistry circuitBreakers,
                     RetryRegistry retries, RateLimiterRegistry rateLimiters, MeterRegistry meters) {
        this.waitInOpenState = settings.circuitBreaker().waitDurationInOpenState();
        this.limitRefreshPeriod = settings.rateLimiter().limitRefreshPeriod();
        this.retry = retries.retry(NAME, retryConfig(settings.retry()));
        this.circuitBreaker = circuitBreakers.circuitBreaker(NAME, circuitBreakerConfig(settings.circuitBreaker()));
        this.rateLimiter = rateLimiters.rateLimiter(NAME, rateLimiterConfig(settings.rateLimiter()));
        this.rejectedByCircuit = Counter.builder(REJECTED_METRIC).tag("reason", "circuit_open")
                .description(REJECTED_DESCRIPTION).register(meters);
        this.rejectedByRateLimiter = Counter.builder(REJECTED_METRIC).tag("reason", "rate_limited")
                .description(REJECTED_DESCRIPTION).register(meters);
        circuitBreaker.getEventPublisher().onStateTransition(event -> {
            CircuitBreaker.StateTransition transition = event.getStateTransition();
            if (transition.getToState() == CircuitBreaker.State.OPEN) {
                openedAtNanos.set(System.nanoTime());
                LOG.warn("Circuito de Factus: {} → {}. Las llamadas a Factus responderán 503 durante {} s; luego se "
                        + "probará de nuevo.", transition.getFromState(), transition.getToState(),
                        waitInOpenState.toSeconds());
            } else {
                LOG.warn("Circuito de Factus: {} → {}.", transition.getFromState(), transition.getToState());
            }
        });
    }

    /** Ejecuta {@code call} (una llamada HTTP) con reintentos, circuit breaker y limitador. */
    FactusResponse call(String description, Supplier<FactusResponse> call) {
        Supplier<FactusResponse> classified = () -> {
            FactusResponse response = call.get();
            if (isRetryableStatus(response.status())) {
                throw new RetryableResponse(response);
            }
            return response;
        };
        Supplier<FactusResponse> decorated = Retry.decorateSupplier(retry,
                CircuitBreaker.decorateSupplier(circuitBreaker, RateLimiter.decorateSupplier(rateLimiter, classified)));
        try {
            return decorated.get();
        } catch (RetryableResponse exhausted) {
            return exhausted.response;
        } catch (CallNotPermittedException open) {
            rejectedByCircuit.increment();
            Duration retryAfter = remainingOpenTime();
            throw new FactusTechnicalException(description + ": circuito de Factus abierto tras fallas repetidas; "
                    + "se volverá a probar en " + retryAfter.toSeconds() + " s", Reason.CIRCUIT_OPEN, retryAfter, open);
        } catch (RequestNotPermitted limited) {
            rejectedByRateLimiter.increment();
            throw new FactusTechnicalException(description + ": límite propio de peticiones a Factus agotado",
                    Reason.RATE_LIMITED, limitRefreshPeriod, limited);
        }
    }

    /** Estado para el health indicator y los logs: nada sensible, solo contadores y tiempos. */
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
        details.put("rateLimiterAvailablePermissions", rateLimiter.getMetrics().getAvailablePermissions());
        return details;
    }

    CircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }

    // ----- Configuración -----

    /** 5xx, 429 y el 409 "pendiente por enviar a la DIAN" merecen otro intento; 401 y 422 nunca. */
    static boolean isRetryableStatus(int status) {
        return status >= 500 || status == 429 || status == 409;
    }

    private static RetryConfig retryConfig(RetrySettings settings) {
        IntervalFunction backoff = IntervalFunction.ofExponentialRandomBackoff(settings.initialWait(),
                settings.multiplier(), settings.jitter());
        return RetryConfig.custom()
                .maxAttempts(settings.maxAttempts())
                .retryOnException(error -> error instanceof FactusTechnicalException
                        || (error instanceof RetryableResponse retryable
                        && worthRetrying(retryable.response, settings.maxRetryAfter())))
                // Ante un 429 con Retry-After razonable se espera lo que pide Factus; si no, backoff exponencial.
                .intervalBiFunction((attempt, outcome) -> {
                    Duration wait = outcome.isLeft() && outcome.getLeft() instanceof RetryableResponse retryable
                            ? retryAfter(retryable.response) : null;
                    return wait != null ? wait.toMillis() : backoff.apply(attempt);
                })
                .build();
    }

    /** Un 429 cuyo Retry-After supera el máximo no se reintenta: el cliente recibe ese Retry-After. */
    private static boolean worthRetrying(FactusResponse response, Duration maxRetryAfter) {
        if (response.status() != 429) {
            return true;
        }
        Duration wait = retryAfter(response);
        return wait == null || wait.compareTo(maxRetryAfter) <= 0;
    }

    /** {@code Retry-After} en segundos (la forma que usa Laravel); una fecha HTTP o un valor raro se ignoran. */
    static Duration retryAfter(FactusResponse response) {
        if (response.status() != 429 || response.retryAfter() == null) {
            return null;
        }
        try {
            long seconds = Long.parseLong(response.retryAfter().strip());
            return seconds >= 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static CircuitBreakerConfig circuitBreakerConfig(CircuitBreakerSettings settings) {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(settings.slidingWindowSize())
                .minimumNumberOfCalls(settings.minimumNumberOfCalls())
                .failureRateThreshold(settings.failureRateThreshold())
                .waitDurationInOpenState(settings.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(settings.permittedNumberOfCallsInHalfOpenState())
                // Solo las fallas técnicas cuentan. Un 409 es de un documento; el limitador es nuestro.
                .recordException(error -> error instanceof FactusTechnicalException
                        || (error instanceof RetryableResponse retryable && retryable.response.status() != 409))
                .ignoreException(error -> error instanceof RequestNotPermitted
                        || (error instanceof RetryableResponse retryable && retryable.response.status() == 409))
                .build();
    }

    private static RateLimiterConfig rateLimiterConfig(RateLimiterSettings settings) {
        return RateLimiterConfig.custom()
                .limitForPeriod(settings.limitForPeriod())
                .limitRefreshPeriod(settings.limitRefreshPeriod())
                .timeoutDuration(settings.timeout())
                .build();
    }

    /** Lo que falta para que el circuito deje pasar una llamada de prueba; al menos 1 s. */
    private Duration remainingOpenTime() {
        long openedAt = openedAtNanos.get();
        Duration elapsed = openedAt == 0 ? Duration.ZERO : Duration.ofNanos(System.nanoTime() - openedAt);
        Duration remaining = waitInOpenState.minus(elapsed);
        long seconds = Math.max(1, (remaining.toMillis() + 999) / 1000);
        return Duration.ofSeconds(seconds);
    }
}
