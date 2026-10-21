package com.facturaflow.invoicing.infrastructure.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Reloj del servicio. La zona importa: la fecha de emisión es la fecha en Colombia, no la del
 * servidor (un contenedor suele estar en UTC, cinco horas adelante). Los tests inyectan un
 * reloj fijo en los casos de uso.
 */
@Configuration(proxyBeanMethods = false)
class ClockConfiguration {

    @Bean
    Clock clock(@Value("${invoicing.time-zone}") ZoneId zone) {
        return Clock.system(zone);
    }
}
