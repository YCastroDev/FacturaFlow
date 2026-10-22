package com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus;

import com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusDtos.TokenResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Token de Factus en memoria (ADR 0008).
 *
 * <ul>
 *   <li><b>Vencimiento:</b> se usa el {@code expires_in} de cada respuesta, menos un margen; se renueva
 *       antes de que Factus lo rechace. El margen nunca supera la mitad de la vida del token, para no
 *       renovar en cada llamada si Factus devolviera una vida corta.</li>
 *   <li><b>Renovación:</b> con el refresh token; si falla (p. ej. revocado), se vuelve a autenticar con
 *       usuario y contraseña. Se guarda siempre el refresh token nuevo, porque Factus los rota.</li>
 *   <li><b>Un solo hilo renueva</b> (bloqueo con doble verificación): si dos hilos renovaran con el
 *       mismo refresh token, el segundo fallaría, porque Factus revoca el anterior al instante. Los
 *       demás hilos esperan y reciben el token nuevo. Leer un token vigente no toma el bloqueo.</li>
 * </ul>
 * Los tokens nunca se registran en los logs.
 */
final class InMemoryTokenProvider implements TokenProvider {

    private static final Logger LOG = LoggerFactory.getLogger(InMemoryTokenProvider.class);

    private final TokenEndpoint endpoint;
    private final Clock clock;
    private final Duration refreshMargin;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile Token current;

    InMemoryTokenProvider(TokenEndpoint endpoint, Clock clock, Duration refreshMargin) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.refreshMargin = Objects.requireNonNull(refreshMargin, "refreshMargin");
    }

    @Override
    public String accessToken() {
        Token token = current;
        if (isUsable(token)) {
            return token.accessToken();
        }
        lock.lock();
        try {
            token = current;   // otro hilo pudo renovarlo mientras esperábamos el bloqueo
            if (isUsable(token)) {
                return token.accessToken();
            }
            Token renewed = obtain(token);
            current = renewed;
            return renewed.accessToken();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void invalidate(String rejectedToken) {
        lock.lock();
        try {
            Token token = current;
            if (token != null && token.accessToken().equals(rejectedToken)) {
                current = token.expired();   // conserva el refresh token: la próxima llamada intenta renovar con él
            }
        } finally {
            lock.unlock();
        }
    }

    private boolean isUsable(Token token) {
        return token != null && clock.instant().isBefore(token.renewAt());
    }

    private Token obtain(Token previous) {
        if (previous != null && previous.refreshToken() != null) {
            try {
                Token refreshed = toToken(endpoint.refreshGrant(previous.refreshToken()));
                LOG.debug("Token de Factus renovado con el refresh token.");
                return refreshed;
            } catch (FactusTechnicalException e) {
                LOG.warn("No se pudo renovar el token de Factus con el refresh token ({}); se vuelve a autenticar.",
                        e.getMessage());
            }
        }
        Token authenticated = toToken(endpoint.passwordGrant());
        LOG.info("Autenticado en Factus; el token vence en {} s.",
                Duration.between(clock.instant(), authenticated.expiresAt()).toSeconds());
        return authenticated;
    }

    private Token toToken(TokenResponse response) {
        if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
            throw new FactusTechnicalException("Factus respondió /oauth/token sin access_token");
        }
        if (response.expiresIn() == null || response.expiresIn() <= 0) {
            throw new FactusTechnicalException("Factus respondió /oauth/token sin un expires_in válido");
        }
        Duration lifetime = Duration.ofSeconds(response.expiresIn());
        Duration margin = refreshMargin.compareTo(lifetime.dividedBy(2)) > 0 ? lifetime.dividedBy(2) : refreshMargin;
        Instant now = clock.instant();
        return new Token(response.accessToken(), response.refreshToken(), now.plus(lifetime),
                now.plus(lifetime).minus(margin));
    }

    /** {@link #toString()} enmascara los tokens. */
    private record Token(String accessToken, String refreshToken, Instant expiresAt, Instant renewAt) {

        Token expired() {
            return new Token(accessToken, refreshToken, expiresAt, Instant.MIN);
        }

        @Override
        public String toString() {
            return "Token[accessToken=" + FactusMasking.mask(accessToken) + ", refreshToken="
                    + FactusMasking.mask(refreshToken) + ", expiresAt=" + expiresAt + "]";
        }
    }
}
