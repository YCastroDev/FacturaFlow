package com.facturaflow.invoicing.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Metadatos del documento OpenAPI que publica springdoc en /v3/api-docs y Swagger UI. */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    @Bean
    OpenAPI invoicingOpenApi() {
        return new OpenAPI().info(new Info()
                .title("FacturaFlow — invoicing")
                .version("v1")
                .description("""
                        Creación, consulta y emisión de facturas electrónicas.

                        * **Idempotencia:** repetir `POST /api/v1/invoices` con el mismo `referenceCode` y el \
                        mismo contenido devuelve la factura existente (200); con otro contenido, 409.
                        * **Errores:** siempre en formato Problem Details (RFC 9457, `application/problem+json`).
                        * **Proveedor falso** (`invoicing.provider=fake`, valor por defecto): la emisión se \
                        simula según la observación de la factura. `SIMULAR_RECHAZO` → factura REJECTED; \
                        `SIMULAR_CAIDA` → 503 (la factura sigue PENDING). Si no contiene ninguna, se emite \
                        con número `FAKE-<referenceCode>`, un CUFE que empieza con 16 ceros y una URL de PDF \
                        en el dominio reservado `.invalid`: nada de eso es válido ante la DIAN.
                        """));
    }
}
