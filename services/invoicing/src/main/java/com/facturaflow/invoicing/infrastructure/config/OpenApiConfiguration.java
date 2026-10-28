package com.facturaflow.invoicing.infrastructure.config;

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
    OpenAPI invoicingOpenApi(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer) {
        OAuthFlow authorizationCode = new OAuthFlow()
                .authorizationUrl(issuer + "/protocol/openid-connect/auth")
                .tokenUrl(issuer + "/protocol/openid-connect/token");
        return new OpenAPI()
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.OAUTH2)
                        .description("Usuario del realm facturaflow (admin.demo o contador.demo).")
                        .flows(new OAuthFlows().authorizationCode(authorizationCode))))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME))
                .info(new Info()
                        .title("FacturaFlow — invoicing")
                        .version("v1")
                        .description("""
                                Creación, consulta y emisión de facturas electrónicas.

                                * **Idempotencia:** repetir `POST /api/v1/invoices` con el mismo `referenceCode` y el \
                                mismo contenido devuelve la factura existente (200); con otro contenido, 409.
                                * **Seguridad:** toda operación exige un token del realm `facturaflow` con el rol \
                                `admin` o `contador` (botón **Authorize**). Sin token, 401; sin el rol, 403.
                                * **Errores:** siempre en formato Problem Details (RFC 9457, `application/problem+json`).
                                * **Proveedor falso** (`invoicing.provider=fake`, valor por defecto): la emisión se \
                                simula según la observación de la factura. `SIMULAR_RECHAZO` → factura REJECTED; \
                                `SIMULAR_CAIDA` → 503 (la factura sigue PENDING). Si no contiene ninguna, se emite \
                                con número `FAKE-<referenceCode>`, un CUFE que empieza con 16 ceros y una URL de PDF \
                                en el dominio reservado `.invalid`: nada de eso es válido ante la DIAN.
                                """));
    }
}
