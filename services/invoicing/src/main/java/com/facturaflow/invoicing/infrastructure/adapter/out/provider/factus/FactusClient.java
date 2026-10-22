package com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus;

import java.util.function.Function;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Llamadas autenticadas a la API v2 de Factus. Ante un 401 invalida el token y reintenta UNA vez
 * (absorbe el caso de un token revocado por una renovación en otro hilo, spike 0001 §1); si vuelve a
 * recibir 401, entrega esa respuesta. Reintentar la validación es seguro: un 401 no procesa nada, y
 * Factus es idempotente por {@code reference_code}.
 */
final class FactusClient {

    static final String VALIDATE_PATH = "/v2/bills/validate";
    static final String PDF_PATH = "/v2/bills/{number}/download-pdf";

    private final FactusHttp http;
    private final TokenProvider tokens;

    FactusClient(FactusHttp http, TokenProvider tokens) {
        this.http = http;
        this.tokens = tokens;
    }

    FactusResponse validateBill(byte[] json) {
        return authenticated("validación de la factura", client -> client.post().uri(VALIDATE_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(json));
    }

    FactusResponse downloadPdf(String number) {
        return authenticated("descarga del PDF", client -> client.get().uri(PDF_PATH, number));
    }

    private FactusResponse authenticated(String description,
                                         Function<RestClient, RestClient.RequestHeadersSpec<?>> request) {
        String token = tokens.accessToken();
        FactusResponse response = send(description, request, token);
        if (response.status() == 401) {
            tokens.invalidate(token);
            response = send(description, request, tokens.accessToken());
        }
        return response;
    }

    private FactusResponse send(String description, Function<RestClient, RestClient.RequestHeadersSpec<?>> request,
                                String token) {
        return http.exchange(description, client -> request.apply(client)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }
}
