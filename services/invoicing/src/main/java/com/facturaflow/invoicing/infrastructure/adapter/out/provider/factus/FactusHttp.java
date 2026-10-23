package com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus;

import java.net.http.HttpClient;
import java.util.function.Function;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@link RestClient} hacia Factus con timeouts explícitos y un User-Agent propio (Cloudflare,
 * delante de Factus, bloquea algunos genéricos: spike 0001, sorpresa 4). Convierte cualquier
 * falla de red o timeout en {@link FactusTechnicalException} y entrega el estado y el cuerpo sin
 * interpretarlos (ningún código HTTP lanza excepción). Cada llamada pasa por {@link FactusResilience}
 * (reintentos, circuit breaker y limitador, ADR 0010): es el único punto por donde sale una petición
 * a Factus, incluidas las del token.
 */
final class FactusHttp {

    private final RestClient restClient;
    private final FactusResilience resilience;

    FactusHttp(FactusProperties properties, FactusResilience resilience) {
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
                .defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /** Ejecuta la petición que arma {@code request}; {@code description} identifica la operación en los errores. */
    FactusResponse exchange(String description, Function<RestClient, RestClient.RequestHeadersSpec<?>> request) {
        return resilience.call(description, () -> send(description, request));
    }

    /** Un solo intento HTTP, sin resiliencia. */
    private FactusResponse send(String description, Function<RestClient, RestClient.RequestHeadersSpec<?>> request) {
        try {
            return request.apply(restClient).exchange((ignored, response) -> new FactusResponse(
                    response.getStatusCode().value(),
                    response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER),
                    response.getBody().readAllBytes()), true);
        } catch (RestClientException e) {
            throw new FactusTechnicalException(description + ": sin respuesta de Factus ("
                    + rootCauseName(e) + ")", e);
        }
    }

    private static String rootCauseName(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName();
    }
}
