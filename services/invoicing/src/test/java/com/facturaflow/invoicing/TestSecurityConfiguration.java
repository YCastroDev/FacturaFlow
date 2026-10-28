package com.facturaflow.invoicing;

import org.springframework.boot.resttestclient.autoconfigure.RestTestClientBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * Seguridad de los tests de integración (ADR 0018): invoicing valida los tokens con las claves de
 * {@link FakeKeycloak} (el validador real de Boot). El {@code RestTestClient} manda por defecto un token de
 * {@code contador}, el usuario natural de invoicing; un test que pruebe otra identidad pone su propia cabecera
 * {@code Authorization}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestSecurityConfiguration {

    public static final String DEFAULT_ROLE = "contador";

    @Bean
    DynamicPropertyRegistrar fakeKeycloakProperties() {
        return registry -> registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                FakeKeycloak::jwkSetUri);
    }

    @Bean
    RestTestClientBuilderCustomizer defaultToken() {
        return builder -> builder.defaultHeader(HttpHeaders.AUTHORIZATION,
                "Bearer " + FakeKeycloak.userToken(DEFAULT_ROLE));
    }
}
