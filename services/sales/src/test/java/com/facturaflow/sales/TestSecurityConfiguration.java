package com.facturaflow.sales;

import org.springframework.boot.resttestclient.autoconfigure.RestTestClientBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * Seguridad de los tests de integración (ADR 0018): sales valida los tokens con las claves de {@link FakeKeycloak}
 * (el validador real de Boot) y pide el token de sales-service a su token endpoint falso. El {@code RestTestClient}
 * manda por defecto un token de {@code vendedor}, el usuario natural de sales; un test que pruebe otra identidad
 * pone su propia cabecera {@code Authorization}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestSecurityConfiguration {

    public static final String DEFAULT_ROLE = "vendedor";

    @Bean
    DynamicPropertyRegistrar fakeKeycloakProperties() {
        return registry -> {
            registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", FakeKeycloak::jwkSetUri);
            registry.add("sales.catalog.auth.token-uri", FakeKeycloak::tokenUri);
            registry.add("sales.catalog.auth.client-secret", () -> "secreto-de-prueba");
        };
    }

    @Bean
    RestTestClientBuilderCustomizer defaultToken() {
        return builder -> builder.defaultHeader(HttpHeaders.AUTHORIZATION,
                "Bearer " + FakeKeycloak.userToken(DEFAULT_ROLE));
    }
}
