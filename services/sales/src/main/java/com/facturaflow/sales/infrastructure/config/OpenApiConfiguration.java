package com.facturaflow.sales.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Metadatos del documento OpenAPI que publica springdoc en /v3/api-docs y Swagger UI. */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    @Bean
    OpenAPI salesOpenApi() {
        return new OpenAPI().info(new Info()
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
                        * **Errores:** siempre en formato Problem Details (RFC 9457, `application/problem+json`).
                        """));
    }
}
