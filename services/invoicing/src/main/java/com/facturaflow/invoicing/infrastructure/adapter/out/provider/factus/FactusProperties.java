package com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuración del adaptador de Factus ({@code invoicing.factus.*}). Las credenciales llegan solo
 * por variables de entorno ({@code FACTUS_*}); si falta alguna, la aplicación no arranca con
 * {@code invoicing.provider=factus}. {@link #toString()} nunca muestra las credenciales.
 *
 * @param numberingRangeId opcional: sin él, Factus usa el rango de facturación por defecto
 * @param tokenRefreshMargin el token se renueva este tiempo antes de que venza
 */
@Validated
@ConfigurationProperties(prefix = "invoicing.factus")
record FactusProperties(
        @NotNull URI baseUrl,
        @NotBlank String clientId,
        @NotBlank String clientSecret,
        @NotBlank String username,
        @NotBlank String password,
        boolean sendEmail,
        @Positive Integer numberingRangeId,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @NotNull Duration tokenRefreshMargin,
        @NotBlank String userAgent) {

    @Override
    public String toString() {
        return "FactusProperties[baseUrl=" + baseUrl + ", clientId=***, clientSecret=***, username=***, "
                + "password=***, sendEmail=" + sendEmail + ", numberingRangeId=" + numberingRangeId
                + ", connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout
                + ", tokenRefreshMargin=" + tokenRefreshMargin + ", userAgent=" + userAgent + "]";
    }
}
