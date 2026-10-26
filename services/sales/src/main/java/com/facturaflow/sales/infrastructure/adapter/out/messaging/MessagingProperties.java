package com.facturaflow.sales.infrastructure.adapter.out.messaging;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuración de la publicación ({@code sales.messaging.*}). La conexión (host, usuario, contraseña,
 * vhost) es la estándar de Spring ({@code spring.rabbitmq.*}), leída de variables de entorno.
 *
 * @param exchange       exchange del servicio, donde publica todos sus eventos (uno por servicio)
 * @param confirmTimeout cuánto se espera la confirmación del broker de un lote; lo que no se confirma a
 *                       tiempo queda pendiente y se reintenta (puede producir un duplicado)
 */
@Validated
@ConfigurationProperties(prefix = "sales.messaging")
record MessagingProperties(@NotBlank String exchange, @NotNull Duration confirmTimeout) {
}
