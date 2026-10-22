package com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusDtos.TokenResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InMemoryTokenProviderTest {

    private static final Duration MARGIN = Duration.ofSeconds(60);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-22T15:00:00Z"));
    private final CountingEndpoint endpoint = new CountingEndpoint();
    private final InMemoryTokenProvider tokens = new InMemoryTokenProvider(endpoint, clock, MARGIN);

    @Test
    @DisplayName("la primera vez se autentica con usuario y contraseña, y después reutiliza el token")
    void authenticatesOnceAndCaches() {
        assertThat(tokens.accessToken()).isEqualTo("access-1");
        assertThat(tokens.accessToken()).isEqualTo("access-1");

        assertThat(endpoint.passwordGrants.get()).isEqualTo(1);
        assertThat(endpoint.refreshGrants.get()).isZero();
    }

    @Test
    @DisplayName("se renueva con el refresh token cuando entra en el margen de vencimiento, no después")
    void renewsWithinTheMargin() {
        tokens.accessToken();

        clock.advance(Duration.ofSeconds(3600).minus(MARGIN).minusSeconds(1));
        assertThat(tokens.accessToken()).isEqualTo("access-1");

        clock.advance(Duration.ofSeconds(1));
        assertThat(tokens.accessToken()).isEqualTo("access-2");
        assertThat(endpoint.refreshTokensUsed).containsExactly("refresh-1");
    }

    @Test
    @DisplayName("usa siempre el refresh token más reciente, porque Factus los rota")
    void usesTheLatestRefreshToken() {
        tokens.accessToken();
        clock.advance(Duration.ofHours(1));
        tokens.accessToken();
        clock.advance(Duration.ofHours(1));
        tokens.accessToken();

        assertThat(endpoint.refreshTokensUsed).containsExactly("refresh-1", "refresh-2");
    }

    @Test
    @DisplayName("si el refresh falla (p. ej. revocado), vuelve a autenticarse con usuario y contraseña")
    void fallsBackToPasswordWhenRefreshFails() {
        tokens.accessToken();
        endpoint.refreshFails = true;
        clock.advance(Duration.ofHours(1));

        assertThat(tokens.accessToken()).isEqualTo("access-2");
        assertThat(endpoint.passwordGrants.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("si tampoco puede autenticarse, la falla técnica se propaga")
    void propagatesAuthenticationFailure() {
        endpoint.passwordFails = true;

        assertThatThrownBy(tokens::accessToken).isInstanceOf(FactusTechnicalException.class);
    }

    @Test
    @DisplayName("invalidar el token actual obliga a renovarlo con el refresh token en la siguiente llamada")
    void invalidateForcesRenewal() {
        String rejected = tokens.accessToken();

        tokens.invalidate(rejected);

        assertThat(tokens.accessToken()).isEqualTo("access-2");
        assertThat(endpoint.refreshTokensUsed).containsExactly("refresh-1");
    }

    @Test
    @DisplayName("invalidar un token viejo no descarta el nuevo que otro hilo ya obtuvo")
    void invalidatingAStaleTokenIsIgnored() {
        String old = tokens.accessToken();
        tokens.invalidate(old);
        String current = tokens.accessToken();

        tokens.invalidate(old);

        assertThat(tokens.accessToken()).isEqualTo(current);
        assertThat(endpoint.grants()).isEqualTo(2);
    }

    @Test
    @DisplayName("el margen nunca supera la mitad de la vida del token, para no renovar en cada llamada")
    void marginIsCappedForShortLivedTokens() {
        endpoint.expiresIn = 60;   // menor que el margen configurado (60 s)
        tokens.accessToken();

        clock.advance(Duration.ofSeconds(29));
        tokens.accessToken();

        assertThat(endpoint.grants()).isEqualTo(1);
    }

    @Test
    @DisplayName("una respuesta sin expires_in no se acepta")
    void rejectsTokenWithoutExpiry() {
        endpoint.expiresIn = 0;

        assertThatThrownBy(tokens::accessToken).isInstanceOf(FactusTechnicalException.class)
                .hasMessageContaining("expires_in");
    }

    @Test
    @DisplayName("con muchos hilos a la vez y sin token, solo uno se autentica y todos reciben el mismo token")
    void onlyOneThreadAuthenticates() throws Exception {
        endpoint.delay = Duration.ofMillis(200);

        Set<String> received = runConcurrently(64, tokens::accessToken);

        assertThat(received).containsExactly("access-1");
        assertThat(endpoint.passwordGrants.get()).isEqualTo(1);
        assertThat(endpoint.maxConcurrentCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("con muchos hilos a la vez y el token vencido, solo uno renueva con el refresh token")
    void onlyOneThreadRefreshes() throws Exception {
        tokens.accessToken();
        clock.advance(Duration.ofHours(1));
        endpoint.delay = Duration.ofMillis(200);

        Set<String> received = runConcurrently(64, tokens::accessToken);

        assertThat(received).containsExactly("access-2");
        assertThat(endpoint.refreshTokensUsed).containsExactly("refresh-1");
        assertThat(endpoint.maxConcurrentCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("si muchos hilos reciben un 401 con el mismo token, se renueva una sola vez")
    void concurrentInvalidationsRenewOnce() throws Exception {
        String rejected = tokens.accessToken();
        endpoint.delay = Duration.ofMillis(100);

        Set<String> received = runConcurrently(32, () -> {
            tokens.invalidate(rejected);
            return tokens.accessToken();
        });

        assertThat(received).containsExactly("access-2");
        assertThat(endpoint.grants()).isEqualTo(2);
    }

    @Test
    @DisplayName("los tokens nunca aparecen completos en el toString de la respuesta")
    void tokenResponseIsMasked() {
        TokenResponse response = new TokenResponse("Bearer", 3600L, "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiJ9.secreto",
                "def50200aabbccddeeff00112233");

        assertThat(response.toString()).doesNotContain("secreto", "aabbccddeeff").contains("eyJ0eX…[", "def502…[");
    }

    /** Arranca {@code threads} hilos que llaman a la vez (latch) y devuelve los resultados distintos. */
    private static Set<String> runConcurrently(int threads, Callable<String> call) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        Set<String> results = ConcurrentHashMap.newKeySet();
        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> futures = IntStream.range(0, threads)
                    .<Future<?>>mapToObj(i -> executor.submit(() -> {
                        start.await();
                        results.add(call.call());
                        return null;
                    }))
                    .toList();
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        }
        return results;
    }

    /** Endpoint falso: numera los tokens, cuenta las llamadas y mide cuántas hubo en paralelo. */
    private static final class CountingEndpoint implements TokenEndpoint {

        final AtomicInteger passwordGrants = new AtomicInteger();
        final AtomicInteger refreshGrants = new AtomicInteger();
        final List<String> refreshTokensUsed = new CopyOnWriteArrayList<>();
        final AtomicInteger concurrentCalls = new AtomicInteger();
        final AtomicInteger maxConcurrentCalls = new AtomicInteger();
        private final AtomicInteger issued = new AtomicInteger();
        volatile Duration delay = Duration.ZERO;
        volatile long expiresIn = 3600;
        volatile boolean refreshFails;
        volatile boolean passwordFails;

        int grants() {
            return passwordGrants.get() + refreshGrants.get();
        }

        @Override
        public TokenResponse passwordGrant() {
            passwordGrants.incrementAndGet();
            return respond(passwordFails);
        }

        @Override
        public TokenResponse refreshGrant(String refreshToken) {
            refreshGrants.incrementAndGet();
            refreshTokensUsed.add(refreshToken);
            return respond(refreshFails);
        }

        private TokenResponse respond(boolean fail) {
            maxConcurrentCalls.accumulateAndGet(concurrentCalls.incrementAndGet(), Math::max);
            try {
                Thread.sleep(delay);
                if (fail) {
                    throw new FactusTechnicalException("rechazado (HTTP 401)");
                }
                int n = issued.incrementAndGet();
                return new TokenResponse("Bearer", expiresIn, "access-" + n, "refresh-" + n);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } finally {
                concurrentCalls.decrementAndGet();
            }
        }
    }
}
