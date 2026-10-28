package com.facturaflow.sales.infrastructure.adapter.out.catalog;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@link RestClient} hacia catalog con timeouts explícitos y el token de {@code sales-service} (ADR 0018). Entrega
 * estado y cuerpo sin interpretarlos y convierte las fallas de red en {@link CatalogTechnicalException}. Es el
 * único punto por donde sale una petición a catalog, y siempre pasa por {@link CatalogResilience}.
 *
 * <p>Seguridad: un 401 de catalog descarta el token en caché y es una falla reintentable (el reintento pide uno
 * nuevo: Keycloak pudo rotar sus claves); un 403 es configuración (el cliente perdió {@code catalog.read}): falla no
 * reintentable y log ERROR. Las dos acaban en 503, nunca en un 500.
 */
final class CatalogHttp {

    private static final Logger LOG = LoggerFactory.getLogger(CatalogHttp.class);

    private final RestClient restClient;
    private final CatalogResilience resilience;
    private final CatalogTokenProvider tokens;

    CatalogHttp(CatalogProperties properties, CatalogResilience resilience, CatalogTokenProvider tokens) {
        this.resilience = resilience;
        this.tokens = tokens;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    CatalogResponse exchange(String description, Function<RestClient, RestClient.RequestHeadersSpec<?>> request) {
        return resilience.call(description, () -> send(description, request));
    }

    /** Un solo intento HTTP (token incluido), sin resiliencia. */
    private CatalogResponse send(String description, Function<RestClient, RestClient.RequestHeadersSpec<?>> request) {
        String token = tokens.accessToken();
        CatalogResponse response;
        try {
            response = request.apply(restClient)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .exchange((ignored, answer) -> new CatalogResponse(
                            answer.getStatusCode().value(), answer.getBody().readAllBytes()), true);
        } catch (RestClientException e) {
            boolean beforeSending = notConnected(e);
            throw new CatalogTechnicalException(description + ": sin respuesta de catalog (" + rootCauseName(e)
                    + (beforeSending ? ", sin conexión" : "") + ")", beforeSending, e);
        }
        if (response.status() == 401) {
            tokens.invalidate();
            throw new CatalogTechnicalException(description + ": catalog rechazó el token de sales-service (HTTP 401)",
                    true, null);
        }
        if (response.status() == 403) {
            LOG.error("{}: catalog negó el acceso a sales-service (HTTP 403). Revise el scope catalog.read del cliente "
                    + "sales-service en el realm.", description);
            throw new CatalogTechnicalException(description + ": catalog negó el acceso a sales-service (HTTP 403)",
                    false, null);
        }
        return response;
    }

    /**
     * La petición no llegó a salir: conexión rechazada, nombre sin resolver o timeout de CONEXIÓN. Repetirla
     * es seguro y barato. Un timeout de lectura no entra aquí: ya consumió el tiempo de espera.
     */
    static boolean notConnected(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof HttpConnectTimeoutException
                    || cause instanceof UnknownHostException || cause instanceof UnresolvedAddressException) {
                return true;
            }
        }
        return false;
    }

    private static String rootCauseName(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName();
    }
}
