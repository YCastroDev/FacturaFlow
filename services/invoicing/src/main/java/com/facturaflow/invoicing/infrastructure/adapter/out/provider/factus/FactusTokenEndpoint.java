package com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus;

import com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusDtos.TokenResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import tools.jackson.core.JacksonException;

/**
 * {@code POST /oauth/token} como {@code application/x-www-form-urlencoded} (spike 0001, sección 1).
 * Cualquier respuesta distinta de 200 es una {@link FactusTechnicalException}; su mensaje solo lleva
 * el estado HTTP, nunca el cuerpo (que podría contener tokens) ni las credenciales.
 */
final class FactusTokenEndpoint implements TokenEndpoint {

    static final String PATH = "/oauth/token";

    private final FactusHttp http;
    private final FactusProperties properties;

    FactusTokenEndpoint(FactusHttp http, FactusProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    @Override
    public TokenResponse passwordGrant() {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "password");
        form.put("client_id", properties.clientId());
        form.put("client_secret", properties.clientSecret());
        form.put("username", properties.username());
        form.put("password", properties.password());
        return request("autenticación con usuario y contraseña", form);
    }

    @Override
    public TokenResponse refreshGrant(String refreshToken) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("client_id", properties.clientId());
        form.put("client_secret", properties.clientSecret());
        form.put("refresh_token", refreshToken);
        return request("renovación con refresh token", form);
    }

    private TokenResponse request(String description, Map<String, String> form) {
        String body = form.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
        FactusResponse response = http.exchange(description, client -> client.post().uri(PATH)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body.getBytes(StandardCharsets.UTF_8)));
        if (response.status() != 200) {
            throw new FactusTechnicalException(description + " rechazada por Factus (HTTP " + response.status() + ")");
        }
        try {
            return FactusJson.MAPPER.readValue(response.body(), TokenResponse.class);
        } catch (JacksonException e) {
            throw new FactusTechnicalException(description + ": respuesta ilegible de /oauth/token");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
