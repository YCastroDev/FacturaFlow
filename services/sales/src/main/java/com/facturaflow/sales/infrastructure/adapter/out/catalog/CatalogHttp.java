package com.facturaflow.sales.infrastructure.adapter.out.catalog;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.function.Function;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@link RestClient} hacia catalog con timeouts explícitos. Entrega estado y cuerpo sin interpretarlos
 * (ningún código HTTP lanza excepción) y convierte las fallas de red en {@link CatalogTechnicalException}.
 * Es el único punto por donde sale una petición a catalog, y siempre pasa por {@link CatalogResilience}.
 */
final class CatalogHttp {

    private final RestClient restClient;
    private final CatalogResilience resilience;

    CatalogHttp(CatalogProperties properties, CatalogResilience resilience) {
        this.resilience = resilience;
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

    /** Un solo intento HTTP, sin resiliencia. */
    private CatalogResponse send(String description, Function<RestClient, RestClient.RequestHeadersSpec<?>> request) {
        try {
            return request.apply(restClient).exchange((ignored, response) -> new CatalogResponse(
                    response.getStatusCode().value(), response.getBody().readAllBytes()), true);
        } catch (RestClientException e) {
            boolean beforeSending = notConnected(e);
            throw new CatalogTechnicalException(description + ": sin respuesta de catalog (" + rootCauseName(e)
                    + (beforeSending ? ", sin conexión" : "") + ")", beforeSending, e);
        }
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
