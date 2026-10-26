package com.facturaflow.sales.infrastructure.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Reloj del servicio. La zona importa: la fecha de la venta es la fecha en Colombia, no la del servidor
 * (un contenedor suele estar en UTC, cinco horas adelante).
 */
@Configuration(proxyBeanMethods = false)
class ClockConfiguration {

    @Bean
    Clock clock(@Value("${sales.time-zone}") ZoneId zone) {
        return Clock.system(zone);
    }
}
