package com.facturaflow.catalog.integration;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

/** Atajos HTTP para los tests punta a punta: cada llamada devuelve estado, headers y cuerpo como texto. */
final class ApiClient {

    private final RestTestClient client;

    ApiClient(RestTestClient client) {
        this.client = client;
    }

    EntityExchangeResult<String> get(String uri, Object... variables) {
        return client.get().uri(uri, variables).exchange().expectBody(String.class).returnResult();
    }

    EntityExchangeResult<String> post(String uri, String body) {
        return client.post().uri(uri).contentType(MediaType.APPLICATION_JSON).body(body).exchange()
                .expectBody(String.class).returnResult();
    }

    EntityExchangeResult<String> post(String uri) {
        return client.post().uri(uri).exchange().expectBody(String.class).returnResult();
    }

    EntityExchangeResult<String> put(String uri, String ifMatch, String body) {
        var request = client.put().uri(uri).contentType(MediaType.APPLICATION_JSON).body(body);
        if (ifMatch != null) {
            request = request.header("If-Match", ifMatch);
        }
        return request.exchange().expectBody(String.class).returnResult();
    }

    static int status(EntityExchangeResult<String> result) {
        return result.getStatus().value();
    }

    static String etag(EntityExchangeResult<String> result) {
        return result.getResponseHeaders().getETag();
    }

    static <T> T json(EntityExchangeResult<String> result, String path) {
        return JsonPath.read(result.getResponseBody(), path);
    }
}
