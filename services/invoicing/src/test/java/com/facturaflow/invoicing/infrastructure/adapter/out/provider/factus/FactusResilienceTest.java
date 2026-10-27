package com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus;

import static com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusTestData.GENEROUS_READ_TIMEOUT;
import static com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusTestData.SHORT_READ_TIMEOUT;
import static com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusTestData.SLOW_RESPONSE;
import static com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusTestData.cashInvoice;
import static com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusTestData.fixture;
import static com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusTestData.issuedCreditInvoice;
import static com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusTestData.partialReturn;
import static com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusTestData.properties;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.invoicing.application.exception.ElectronicInvoiceProviderUnavailableException;
import com.facturaflow.invoicing.application.exception.ElectronicInvoiceProviderUnavailableException.Reason;
import com.facturaflow.invoicing.application.port.out.IssuanceResult;
import com.facturaflow.invoicing.domain.model.CreditNote;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusProperties.CircuitBreakerSettings;
import com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusProperties.RateLimiterSettings;
import com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusProperties.Resilience;
import com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusProperties.RetrySettings;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Reintentos, circuit breaker y limitador de las llamadas a Factus (ADR 0010), con el adaptador real
 * contra WireMock (escenarios con estado). Los tiempos son de milisegundos, configurados solo aquí,
 * para que todo corra en segundos. Ninguna aserción depende de que el runner sea rápido: el circuito
 * abierto dura {@link #LONG_OPEN_WAIT} (salvo en el test que espera el semiabierto, donde dormir de más
 * es inocuo) y los timeouts tienen segundos de margen ({@link FactusTestData#GENEROUS_READ_TIMEOUT}).
 */
@ExtendWith(OutputCaptureExtension.class)
class FactusResilienceTest {

    private static final String BILLS = FactusClient.VALIDATE_PATH;
    private static final String NOTES = FactusClient.CREDIT_NOTE_VALIDATE_PATH;
    private static final String TOKEN = FactusTokenEndpoint.PATH;
    /** Mientras dure, una pausa del runner no puede pasar el circuito a semiabierto a mitad de un test. */
    private static final Duration LONG_OPEN_WAIT = Duration.ofSeconds(60);
    /** Solo para el test que ESPERA el semiabierto (dormir de más ahí no cambia el resultado). */
    private static final Duration SHORT_OPEN_WAIT = Duration.ofMillis(400);

    @RegisterExtension
    static final WireMockExtension FACTUS = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private final UUID id = UUID.randomUUID();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-23T15:00:00Z"));
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    /** 3 intentos con esperas de ms; circuito que abre con 4 llamadas y 50 % de fallas. */
    private static Resilience fast(RateLimiterSettings rateLimiter, Duration openWait) {
        return new Resilience(new RetrySettings(3, Duration.ofMillis(20), 2.0, 0.5, Duration.ofSeconds(2)),
                new CircuitBreakerSettings(4, 4, 50, openWait, 1), rateLimiter);
    }

    private static Resilience fast(RateLimiterSettings rateLimiter) {
        return fast(rateLimiter, LONG_OPEN_WAIT);
    }

    private static Resilience fast(Duration openWait) {
        return fast(new RateLimiterSettings(1000, Duration.ofSeconds(1), Duration.ZERO), openWait);
    }

    private static Resilience fast() {
        return fast(LONG_OPEN_WAIT);
    }

    private FactusResilience resilience;
    private FactusElectronicInvoiceProvider provider;

    private void start(Resilience settings, Duration readTimeout) {
        FactusProperties properties = properties(FACTUS.baseUrl(), readTimeout, settings);
        resilience = new FactusResilience(settings, CircuitBreakerRegistry.ofDefaults(), RetryRegistry.ofDefaults(),
                RateLimiterRegistry.ofDefaults(), meters);
        provider = FactusElectronicInvoiceProvider.create(properties, clock, resilience);
        FACTUS.stubFor(post(urlEqualTo(TOKEN)).willReturn(json(200, "{\"token_type\":\"Bearer\",\"expires_in\":3600,"
                + "\"access_token\":\"access-res-0123456789abcdef\",\"refresh_token\":\"refresh-res-0123456789\"}")));
    }

    private void start(Resilience settings) {
        start(settings, GENEROUS_READ_TIMEOUT);
    }

    private static ResponseDefinitionBuilder json(int status, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
    }

    /** Responde en orden: el primero, luego el segundo, ... y el último para siempre (escenario con estado). */
    private static void respondInOrder(String path, ResponseDefinitionBuilder... responses) {
        for (int i = 0; i < responses.length; i++) {
            String state = i == 0 ? Scenario.STARTED : "paso-" + i;
            var stub = post(urlEqualTo(path)).inScenario("orden-" + path).whenScenarioStateIs(state)
                    .willReturn(responses[i]);
            if (i < responses.length - 1) {
                stub = stub.willSetStateTo("paso-" + (i + 1));
            }
            FACTUS.stubFor(stub);
        }
    }

    private static int requests(String path) {
        return FACTUS.findAll(postRequestedFor(urlEqualTo(path))).size();
    }

    private Invoice cash() {
        return cashInvoice(id);
    }

    private ResponseDefinitionBuilder validatedCash() {
        return json(201, fixture("bill-validated-cash.json", id));
    }

    private static ResponseDefinitionBuilder error500() {
        return json(500, fixture("error-500.json"));
    }

    private ElectronicInvoiceProviderUnavailableException unavailable(Runnable call) {
        try {
            call.run();
        } catch (ElectronicInvoiceProviderUnavailableException e) {
            return e;
        }
        throw new AssertionError("Se esperaba una falla técnica");
    }

    @Nested
    @DisplayName("reintentos")
    class Retries {

        @Test
        @DisplayName("falla, falla, éxito → el reintento lo resuelve: la factura se emite con 3 peticiones")
        void failFailSucceed() {
            start(fast());
            respondInOrder(BILLS, error500(), json(503, "{}"), validatedCash());

            assertThat(provider.issue(cash())).isInstanceOf(IssuanceResult.Issued.class);
            assertThat(requests(BILLS)).isEqualTo(3);
        }

        @Test
        @DisplayName("también en notas crédito: un 500 y luego la nota validada")
        void creditNoteRetried() {
            start(fast());
            UUID noteId = UUID.randomUUID();
            Invoice invoice = issuedCreditInvoice(UUID.randomUUID());
            CreditNote note = partialReturn(noteId, invoice);
            respondInOrder(NOTES, error500(), json(201, fixture("credit-note-validated-partial.json", noteId)));

            assertThat(provider.issue(note, invoice)).isInstanceOf(IssuanceResult.Issued.class);
            assertThat(requests(NOTES)).isEqualTo(2);
        }

        @Test
        @DisplayName("un timeout también se reintenta (Factus es idempotente por reference_code)")
        void timeoutRetried() {
            start(fast(), SHORT_READ_TIMEOUT);
            respondInOrder(BILLS, validatedCash().withFixedDelay((int) SLOW_RESPONSE.toMillis()), validatedCash());

            assertThat(provider.issue(cash())).isInstanceOf(IssuanceResult.Issued.class);
            assertThat(requests(BILLS)).isEqualTo(2);
        }

        @Test
        @DisplayName("un 422 (rechazo por datos) → un solo intento y Rejected, sin reintentos")
        void rejectionIsNotRetried() {
            start(fast());
            respondInOrder(BILLS, json(422, fixture("bill-422-wrong-dv.json")));

            assertThat(provider.issue(cash())).isInstanceOf(IssuanceResult.Rejected.class);
            assertThat(requests(BILLS)).isEqualTo(1);
        }

        @Test
        @DisplayName("una identidad distinta (otra factura) → un solo intento: no es una falla de red")
        void identityMismatchIsNotRetried() {
            start(fast());
            respondInOrder(BILLS, json(201, fixture("bill-validated-cash.json", UUID.randomUUID())));

            assertThatThrownBy(() -> provider.issue(cash()))
                    .isInstanceOf(ElectronicInvoiceProviderUnavailableException.class)
                    .hasMessageContaining("no corresponde");
            assertThat(requests(BILLS)).isEqualTo(1);
        }

        @Test
        @DisplayName("el 401 se sigue resolviendo como antes (renovar el token y un solo reintento), sin duplicarse")
        void unauthorizedHandledOnce() {
            start(fast());
            respondInOrder(BILLS, json(401, fixture("error-401.json")), validatedCash());

            assertThat(provider.issue(cash())).isInstanceOf(IssuanceResult.Issued.class);
            assertThat(requests(BILLS)).isEqualTo(2);
        }

        @Test
        @DisplayName("un 409 'pendiente' se reintenta, pero no cuenta como falla del circuito (es de UN documento)")
        void pendingIsRetriedButNotCounted() {
            start(fast());
            respondInOrder(BILLS, json(409, fixture("bill-409-pending.json")));

            assertThatThrownBy(() -> provider.issue(cash())).hasMessageContaining("HTTP 409");
            assertThat(requests(BILLS)).isEqualTo(3);
            assertThat(resilience.circuitBreaker().getMetrics().getNumberOfFailedCalls()).isZero();
        }

        @Test
        @DisplayName("un 429 con Retry-After corto se reintenta después de esa espera")
        void shortRetryAfterIsHonoured() {
            start(fast());
            respondInOrder(BILLS, json(429, fixture("error-429.json")).withHeader("Retry-After", "1"), validatedCash());

            long started = System.nanoTime();
            assertThat(provider.issue(cash())).isInstanceOf(IssuanceResult.Issued.class);

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofSeconds(1));
            assertThat(requests(BILLS)).isEqualTo(2);
        }

        @Test
        @DisplayName("un 429 con Retry-After largo no se reintenta: 503 con ese mismo Retry-After para el cliente")
        void longRetryAfterIsPropagated() {
            start(fast());
            respondInOrder(BILLS, json(429, fixture("error-429.json")).withHeader("Retry-After", "60"));

            ElectronicInvoiceProviderUnavailableException error = unavailable(() -> provider.issue(cash()));

            assertThat(error.reason()).isEqualTo(Reason.RATE_LIMITED);
            assertThat(error.retryAfter()).contains(Duration.ofSeconds(60));
            assertThat(requests(BILLS)).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("circuit breaker")
    class Breaker {

        @Test
        @DisplayName("fallas repetidas abren el circuito: las siguientes llamadas (facturas y notas) no llegan a Factus")
        void opensAndShortCircuits(CapturedOutput output) {
            start(fast());
            respondInOrder(BILLS, error500());
            // token (éxito) + 3 intentos fallidos = 4 llamadas con 75 % de fallas → se abre
            assertThatThrownBy(() -> provider.issue(cash())).hasMessageContaining("HTTP 500");
            assertThat(resilience.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
            int before = requests(BILLS);

            ElectronicInvoiceProviderUnavailableException error = unavailable(() -> provider.issue(cash()));
            Invoice invoice = issuedCreditInvoice(UUID.randomUUID());
            ElectronicInvoiceProviderUnavailableException noteError =
                    unavailable(() -> provider.issue(partialReturn(UUID.randomUUID(), invoice), invoice));

            assertThat(requests(BILLS)).isEqualTo(before);
            assertThat(requests(NOTES)).isZero();
            assertThat(error.reason()).isEqualTo(Reason.CIRCUIT_OPEN);
            // Lo que falta de los 60 s (el redondeo hacia arriba se prueba aparte: retryAfterRoundsUp).
            assertThat(error.retryAfter()).hasValueSatisfying(wait ->
                    assertThat(wait).isBetween(LONG_OPEN_WAIT.minusSeconds(5), LONG_OPEN_WAIT));
            assertThat(noteError.reason()).isEqualTo(Reason.CIRCUIT_OPEN);
            assertThat(noteError.document()).isEqualTo(ElectronicInvoiceProviderUnavailableException.Document.CREDIT_NOTE);
            assertThat(output).contains("Circuito de Factus: CLOSED → OPEN");
            assertThat(meters.counter(FactusResilience.REJECTED_METRIC, "reason", "circuit_open").count()).isEqualTo(2);
            assertThat(resilience.snapshot()).containsEntry("circuitBreaker", "OPEN").containsKey("retryAfterSeconds");
        }

        @Test
        @DisplayName("tras la espera pasa a semiabierto y, si Factus responde bien, se cierra")
        void halfOpenThenClosed(CapturedOutput output) throws InterruptedException {
            start(fast(SHORT_OPEN_WAIT));
            respondInOrder(BILLS, error500(), error500(), error500(), validatedCash());
            assertThatThrownBy(() -> provider.issue(cash()));
            assertThat(resilience.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);

            Thread.sleep(SHORT_OPEN_WAIT.plusMillis(100).toMillis());

            assertThat(provider.issue(cash())).isInstanceOf(IssuanceResult.Issued.class);
            assertThat(resilience.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(output).contains("OPEN → HALF_OPEN", "HALF_OPEN → CLOSED");
        }

        @ParameterizedTest(name = "faltan {0} ms → Retry-After de {1} s")
        @CsvSource({"400, 1", "1000, 1", "1001, 2", "29999, 30", "0, 1", "-250, 1"})
        @DisplayName("el Retry-After del circuito abierto son segundos enteros hacia arriba, nunca menos de 1 s")
        void retryAfterRoundsUp(long remainingMillis, long expectedSeconds) {
            assertThat(FactusResilience.wholeSecondsRoundedUp(Duration.ofMillis(remainingMillis)))
                    .isEqualTo(Duration.ofSeconds(expectedSeconds));
        }

        @Test
        @DisplayName("los rechazos de la DIAN (422) no abren el circuito, por muchos que sean")
        void rejectionsDoNotOpen() {
            start(fast());
            respondInOrder(BILLS, json(422, fixture("bill-422-wrong-dv.json")));

            for (int i = 0; i < 6; i++) {
                assertThat(provider.issue(cash())).isInstanceOf(IssuanceResult.Rejected.class);
            }
            assertThat(resilience.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }
    }

    @Nested
    @DisplayName("limitador de peticiones")
    class Limiter {

        @Test
        @DisplayName("agotado → 503 tras una espera corta, sin llamar a Factus ni contar como falla del circuito")
        void exhaustedGives503() {
            // 2 permisos por periodo largo: el token y la primera validación los consumen.
            start(fast(new RateLimiterSettings(2, Duration.ofSeconds(30), Duration.ofMillis(100))));
            respondInOrder(BILLS, validatedCash());
            assertThat(provider.issue(cash())).isInstanceOf(IssuanceResult.Issued.class);

            long started = System.nanoTime();
            ElectronicInvoiceProviderUnavailableException error = unavailable(() -> provider.issue(cash()));

            // Esperó los 100 ms del limitador, no el periodo de 30 s (margen amplio para runners lentos).
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
            assertThat(error.reason()).isEqualTo(Reason.RATE_LIMITED);
            assertThat(error.retryAfter()).contains(Duration.ofSeconds(30));
            assertThat(requests(BILLS)).isEqualTo(1);
            assertThat(resilience.circuitBreaker().getMetrics().getNumberOfFailedCalls()).isZero();
            assertThat(meters.counter(FactusResilience.REJECTED_METRIC, "reason", "rate_limited").count()).isEqualTo(1);
        }
    }
}
