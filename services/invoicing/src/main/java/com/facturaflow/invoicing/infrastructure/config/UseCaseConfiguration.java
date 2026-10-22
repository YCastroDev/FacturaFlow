package com.facturaflow.invoicing.infrastructure.config;

import com.facturaflow.invoicing.application.port.in.CreateInvoiceUseCase;
import com.facturaflow.invoicing.application.port.in.GetInvoicePdfUseCase;
import com.facturaflow.invoicing.application.port.in.GetInvoiceUseCase;
import com.facturaflow.invoicing.application.port.in.IssueInvoiceUseCase;
import com.facturaflow.invoicing.application.port.out.ElectronicInvoiceProvider;
import com.facturaflow.invoicing.application.port.out.InvoiceRepository;
import com.facturaflow.invoicing.application.service.CreateInvoiceService;
import com.facturaflow.invoicing.application.service.GetInvoicePdfService;
import com.facturaflow.invoicing.application.service.GetInvoiceService;
import com.facturaflow.invoicing.application.service.IssueInvoiceService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registra los casos de uso (Java puro) como beans y les conecta sus puertos de salida.
 * A propósito NO se envuelven en transacciones: cada operación del repositorio es su propia
 * transacción, y así la llamada al proveedor de facturación queda fuera de la base (ADR 0006).
 */
@Configuration(proxyBeanMethods = false)
class UseCaseConfiguration {

    @Bean
    CreateInvoiceUseCase createInvoiceUseCase(InvoiceRepository repository, Clock clock) {
        return new CreateInvoiceService(repository, clock);
    }

    @Bean
    GetInvoiceUseCase getInvoiceUseCase(InvoiceRepository repository) {
        return new GetInvoiceService(repository);
    }

    @Bean
    IssueInvoiceUseCase issueInvoiceUseCase(InvoiceRepository repository, ElectronicInvoiceProvider provider) {
        return new IssueInvoiceService(repository, provider);
    }

    @Bean
    GetInvoicePdfUseCase getInvoicePdfUseCase(InvoiceRepository repository, ElectronicInvoiceProvider provider) {
        return new GetInvoicePdfService(repository, provider);
    }
}
