package com.facturaflow.catalog.config;

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
    OpenAPI catalogOpenApi(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer) {
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
                        .title("FacturaFlow — catalog")
                        .version("v1")
                        .description("""
                                Productos y clientes de la pyme (datos maestros). Las ventas los consultan aquí; las \
                                facturas guardan una copia de sus datos.

                                * **No se borran:** se desactivan (`POST /{id}/deactivate`) y se reactivan \
                                (`POST /{id}/activate`), porque ventas y facturas los referencian.
                                * **Actualizar exige `If-Match`** (bloqueo optimista con ETag, RFC 9110):
                                  1. Consulte el registro (`GET /{id}`). La respuesta trae el header `ETag`, p. ej. \
                                `"0"` (en Swagger UI: *Responses → Response headers*). Es la versión del registro, \
                                también disponible en el campo `version`.
                                  2. Envíe el `PUT` con el header `If-Match: "0"` y el cuerpo completo.
                                  3. Si alguien lo cambió mientras tanto, responde **412** y no modifica nada: vuelva a \
                                consultarlo. Sin `If-Match` responde **428**.
                                * **Identidad inmutable:** el código del producto y el documento del cliente no cambian \
                                (422); para corregirlos, desactive el registro y cree otro.
                                * **Listados:** `page` (desde 0), `size` (1 a 100), `q` (texto) y `active`.
                                * **Seguridad:** token del realm `facturaflow` (botón **Authorize**). Consultar: \
                                `admin` o `vendedor`. Crear y editar clientes: `admin` o `vendedor`. Crear y editar \
                                productos, y activar o desactivar cualquier registro: solo `admin`. Sin token, 401; \
                                sin el rol, 403.
                                * **Errores:** siempre en formato Problem Details (RFC 9457, `application/problem+json`).
                                """));
    }
}
