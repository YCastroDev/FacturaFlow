package com.facturaflow.sales.infrastructure.adapter.out.catalog;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ClientCredentialsOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

/**
 * Token de acceso de {@code sales-service} para llamar a catalog (ADR 0018): client credentials contra Keycloak,
 * con Spring Security OAuth2 Client. El token se guarda en memoria y se renueva cuando le falta menos de
 * {@code renew-before-expiry} para vencer; nada se pide al arrancar.
 *
 * <p>Se pide DENTRO de {@link CatalogHttp#exchange}, es decir, dentro del reintento y del circuito de catalog: para
 * sales la dependencia es "catalog con mi identidad". Una falla de Keycloak es una {@link CatalogTechnicalException}:
 * <ul>
 *   <li>sin conexión, o 502/503/504 del token endpoint: reintentable (un reinicio de Keycloak);</li>
 *   <li>credenciales rechazadas u otra respuesta: no reintentable y log ERROR (es configuración);</li>
 * </ul>
 * y acaba, como cualquier falla de catalog, en 503 {@code catalog-unavailable} con {@code Retry-After}.
 * Nunca se registra el token ni el secreto.
 */
final class CatalogTokenProvider {

    static final String REGISTRATION_ID = "sales-service";

    private static final Logger LOG = LoggerFactory.getLogger(CatalogTokenProvider.class);

    private final AuthorizedClientServiceOAuth2AuthorizedClientManager manager;
    private final OAuth2AuthorizedClientService authorizedClients;
    private final OAuth2AuthorizeRequest request;

    CatalogTokenProvider(CatalogProperties.Auth auth, Clock clock) {
        ClientRegistration registration = ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientId(auth.clientId())
                .clientSecret(auth.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri(auth.tokenUri().toString())
                .build();
        InMemoryClientRegistrationRepository registrations = new InMemoryClientRegistrationRepository(registration);
        this.authorizedClients = new InMemoryOAuth2AuthorizedClientService(registrations);

        RestClientClientCredentialsTokenResponseClient tokenClient = new RestClientClientCredentialsTokenResponseClient();
        tokenClient.setRestClient(tokenRestClient(auth));
        ClientCredentialsOAuth2AuthorizedClientProvider provider = new ClientCredentialsOAuth2AuthorizedClientProvider();
        provider.setAccessTokenResponseClient(tokenClient);
        provider.setClockSkew(auth.renewBeforeExpiry());
        provider.setClock(clock);

        this.manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, authorizedClients);
        this.manager.setAuthorizedClientProvider(provider);
        this.request = OAuth2AuthorizeRequest.withClientRegistrationId(REGISTRATION_ID)
                .principal(REGISTRATION_ID)
                .build();
    }

    /** El token vigente (el de la caché, o uno nuevo si falta poco para que venza). */
    String accessToken() {
        OAuth2AuthorizedClient client;
        try {
            client = manager.authorize(request);
        } catch (OAuth2AuthorizationException failed) {
            throw classify(failed);
        }
        if (client == null) {
            throw new CatalogTechnicalException("Token de sales-service: Keycloak no entregó un token", false, null);
        }
        return client.getAccessToken().getTokenValue();
    }

    /** Descarta el token en caché (catalog lo rechazó): el próximo intento pide uno nuevo. */
    void invalidate() {
        authorizedClients.removeAuthorizedClient(REGISTRATION_ID, REGISTRATION_ID);
    }

    private static CatalogTechnicalException classify(OAuth2AuthorizationException failed) {
        if (CatalogHttp.notConnected(failed)) {
            return new CatalogTechnicalException("Token de sales-service: sin conexión con Keycloak", true, failed);
        }
        HttpStatusCodeException httpError = cause(failed, HttpStatusCodeException.class);
        if (httpError != null && httpError.getStatusCode().is5xxServerError()) {
            int status = httpError.getStatusCode().value();
            return new CatalogTechnicalException("Token de sales-service: Keycloak respondió HTTP " + status,
                    CatalogResilience.isRetryableStatus(status), failed);
        }
        if (httpError == null && cause(failed, IOException.class) != null) {
            // Timeout de lectura u otra falla de E/S tras conectar: no se repite (ya gastó el tiempo de espera).
            return new CatalogTechnicalException("Token de sales-service: Keycloak no respondió a tiempo", false,
                    failed);
        }
        // Keycloak rechazó la solicitud (401 con credenciales inválidas, o 400 con un código OAuth2 como
        // invalid_scope): es configuración. Solo el estado o el código, nunca la descripción ni el cuerpo.
        String why = httpError != null ? "HTTP " + httpError.getStatusCode().value() : failed.getError().getErrorCode();
        LOG.error("Keycloak rechazó la solicitud de token de sales-service ({}): revise SALES_SERVICE_CLIENT_SECRET y "
                + "el cliente sales-service del realm", why);
        return new CatalogTechnicalException("Token de sales-service: Keycloak rechazó la solicitud (" + why + ")",
                false, failed);
    }

    private static <T extends Throwable> T cause(Throwable error, Class<T> type) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return type.cast(cause);
            }
        }
        return null;
    }

    /** El mismo cliente que usa Spring por defecto, con los timeouts propios ({@code sales.catalog.auth.*}). */
    private static RestClient tokenRestClient(CatalogProperties.Auth auth) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(auth.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(auth.readTimeout());
        return RestClient.builder()
                .requestFactory(requestFactory)
                .configureMessageConverters(converters -> {
                    converters.addCustomConverter(new FormHttpMessageConverter());
                    converters.addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter());
                })
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build();
    }
}
