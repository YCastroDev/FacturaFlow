package com.facturaflow.catalog.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Metadatos del documento OpenAPI que publica springdoc en /v3/api-docs y Swagger UI. */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    @Bean
    OpenAPI catalogOpenApi() {
        return new OpenAPI().info(new Info()
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
                        * **Errores:** siempre en formato Problem Details (RFC 9457, `application/problem+json`).
                        """));
    }
}
