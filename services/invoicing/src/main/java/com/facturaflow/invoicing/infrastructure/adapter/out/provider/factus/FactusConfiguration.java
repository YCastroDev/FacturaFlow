package com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus;

import com.facturaflow.invoicing.application.port.out.ElectronicCreditNoteProvider;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Activa el adaptador de Factus con {@code invoicing.provider=factus} (ADR 0008). Solo entonces se
 * enlazan y validan las propiedades {@code invoicing.factus.*}: con el proveedor falso no se
 * necesitan credenciales. Vive dentro del paquete del adaptador para que nada de Factus sea visible
 * fuera de él (regla de ArchUnit).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "invoicing.provider", havingValue = "factus")
@EnableConfigurationProperties(FactusProperties.class)
class FactusConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(FactusConfiguration.class);

    @Bean
    FactusElectronicInvoiceProvider factusElectronicInvoiceProvider(FactusProperties properties, Clock clock) {
        LOG.info("Proveedor de facturación electrónica: Factus ({}), send_email={}, numbering_range_id={}.",
                properties.baseUrl(), properties.sendEmail(),
                properties.numberingRangeId() == null ? "por defecto" : properties.numberingRangeId());
        return FactusElectronicInvoiceProvider.create(properties, clock);
    }

    /** Temporal hasta que el adaptador de Factus implemente las notas crédito (ADR 0009). */
    @Bean
    ElectronicCreditNoteProvider factusElectronicCreditNoteProvider() {
        LOG.warn("Notas crédito con Factus aún no implementadas: su emisión responde 503.");
        return new FactusCreditNotesNotYetSupported();
    }
}
