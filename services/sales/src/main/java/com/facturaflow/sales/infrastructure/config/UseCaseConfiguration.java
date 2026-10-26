package com.facturaflow.sales.infrastructure.config;

import com.facturaflow.sales.application.port.in.CreateSaleUseCase;
import com.facturaflow.sales.application.port.in.GetSaleUseCase;
import com.facturaflow.sales.application.port.in.ListSalesUseCase;
import com.facturaflow.sales.application.port.out.CatalogClient;
import com.facturaflow.sales.application.port.out.SaleRepository;
import com.facturaflow.sales.application.service.CreateSaleService;
import com.facturaflow.sales.application.service.GetSaleService;
import com.facturaflow.sales.application.service.ListSalesService;
import java.time.Clock;
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
}
