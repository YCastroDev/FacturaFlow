package com.facturaflow.sales.infrastructure.config;

import com.facturaflow.sales.application.port.in.CreateSaleUseCase;
import com.facturaflow.sales.application.port.in.GetOutboxStatusUseCase;
import com.facturaflow.sales.application.port.in.GetSaleUseCase;
import com.facturaflow.sales.application.port.in.ListSalesUseCase;
import com.facturaflow.sales.application.port.in.PublishPendingEventsUseCase;
import com.facturaflow.sales.application.port.in.RecordInvoiceResultUseCase;
import com.facturaflow.sales.application.port.out.CatalogClient;
import com.facturaflow.sales.application.port.out.EventPublisher;
import com.facturaflow.sales.application.port.out.OutboxRepository;
import com.facturaflow.sales.application.port.out.SaleRepository;
import com.facturaflow.sales.application.service.CreateSaleService;
import com.facturaflow.sales.application.service.GetOutboxStatusService;
import com.facturaflow.sales.application.service.GetSaleService;
import com.facturaflow.sales.application.service.ListSalesService;
import com.facturaflow.sales.application.service.PublishPendingEventsService;
import com.facturaflow.sales.application.service.RecordInvoiceResultService;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registra los casos de uso (Java puro) como beans. Sin transacciones: viven en el adaptador de persistencia. */
@Configuration(proxyBeanMethods = false)
class UseCaseConfiguration {

    @Bean
    CreateSaleUseCase createSaleUseCase(SaleRepository repository, CatalogClient catalog, Clock clock) {
        return new CreateSaleService(repository, catalog, clock);
    }

    @Bean
    GetSaleUseCase getSaleUseCase(SaleRepository repository) {
        return new GetSaleService(repository);
    }

    @Bean
    ListSalesUseCase listSalesUseCase(SaleRepository repository) {
        return new ListSalesService(repository);
    }

    @Bean
    PublishPendingEventsUseCase publishPendingEventsUseCase(OutboxRepository outbox, EventPublisher publisher,
                                                            @Value("${sales.outbox.batch-size}") int batchSize) {
        return new PublishPendingEventsService(outbox, publisher, batchSize);
    }

    @Bean
    GetOutboxStatusUseCase getOutboxStatusUseCase(OutboxRepository outbox) {
        return new GetOutboxStatusService(outbox);
    }

    @Bean
    RecordInvoiceResultUseCase recordInvoiceResultUseCase(SaleRepository repository) {
        return new RecordInvoiceResultService(repository);
    }
}
