package com.facturaflow.sales.infrastructure.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Metadatos del documento OpenAPI que publica springdoc en /v3/api-docs y Swagger UI, con el esquema OAuth2 del
 * botón "Authorize" (ADR 0018): authorization code + PKCE contra el realm, con el cliente público
 * {@code facturaflow-swagger}. Las URLs salen del issuer, que es la dirección de Keycloak que ve el navegador.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    static final String SECURITY_SCHEME = "keycloak";

    @Bean
    OpenAPI salesOpenApi(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer) {
        OAuthFlow authorizationCode = new OAuthFlow()
                .authorizationUrl(issuer + "/protocol/openid-connect/auth")
                .tokenUrl(issuer + "/protocol/openid-connect/token");
        return new OpenAPI()
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.OAUTH2)
                        .description("Usuario del realm facturaflow (admin.demo o vendedor.demo).")
                        .flows(new OAuthFlows().authorizationCode(authorizationCode))))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME))
                .info(new Info()
                        .title("FacturaFlow — sales")
                        .version("v1")
                        .description("""
                                Ventas confirmadas. El cliente y los productos se toman de **catalog** por HTTP: la \
                                petición solo trae el documento del cliente, los códigos, cantidades y descuentos, y la \
                                forma de pago. La venta guarda una copia de lo que devolvió catalog.

                                * **Datos de ejemplo:** ejecute `scripts/seed-catalog.sh` para crear en catalog los \
                                clientes y productos que usan los ejemplos.
                                * **Idempotencia:** repetir `POST /api/v1/sales` con el mismo `referenceCode` y el mismo \
                                contenido devuelve la venta existente (200); con otro contenido, 409.
                                * **Catalog caído:** 503 con `Retry-After`; la venta no se crea.
                                * **Seguridad:** toda operación exige un token del realm `facturaflow` con el rol `admin` o \
                                `vendedor` (botón **Authorize**). Sin token, 401; sin el rol, 403.
                                * **Errores:** siempre en formato Problem Details (RFC 9457, `application/problem+json`).
                                """));
    }
}
