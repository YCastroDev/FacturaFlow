package com.facturaflow.invoicing.infrastructure.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.facturaflow.invoicing.TestcontainersConfiguration;
import com.facturaflow.invoicing.application.port.in.CreateInvoiceCommand;
import com.facturaflow.invoicing.application.port.in.CreateInvoiceUseCase;
import com.facturaflow.invoicing.application.port.in.IssueInvoiceCommand;
import com.facturaflow.invoicing.application.port.in.IssueInvoiceUseCase;
import com.facturaflow.invoicing.application.port.out.ElectronicInvoiceProvider;
import com.facturaflow.invoicing.application.port.out.IssuanceResult;
import com.facturaflow.invoicing.domain.model.DocumentType;
import com.facturaflow.invoicing.domain.model.InvoiceStatus;
import com.facturaflow.invoicing.domain.model.PaymentMethod;
import com.facturaflow.invoicing.domain.model.TaxType;
import com.facturaflow.invoicing.domain.model.UnitOfMeasure;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifica en tiempo de ejecución (con Spring, JPA y PostgreSQL reales) que el caso de uso
 * llama al proveedor FUERA de toda transacción. El proveedor de prueba anota si había una
 * transacción activa en el momento de la llamada. La regla de ArchUnit lo protege de forma
 * estática; este test, con el cableado real.
 */
@SpringBootTest(properties = "invoicing.provider=recording")
@Import({TestcontainersConfiguration.class, ProviderCalledOutsideTransactionTest.RecordingProviderConfiguration.class})
class ProviderCalledOutsideTransactionTest {

    @Autowired
    private CreateInvoiceUseCase createInvoice;

    @Autowired
    private IssueInvoiceUseCase issueInvoice;

    @Autowired
    private RecordingProvider provider;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE TABLE invoices CASCADE").update();
        provider.transactionActiveOnCall.clear();
    }

    @Test
    @DisplayName("al emitir, el proveedor se llama sin ninguna transacción de base de datos abierta")
    void providerIsCalledOutsideTransaction() {
        var invoice = createInvoice.create(new CreateInvoiceCommand("SALE-TX",
                new CreateInvoiceCommand.Customer(DocumentType.NIT, "890903938", 8, "Cliente", null, null),
                List.of(new CreateInvoiceCommand.Line("SKU-1", "Producto", BigDecimal.ONE, new BigDecimal("1000"), null,
                        UnitOfMeasure.UNIT, false, List.of(new CreateInvoiceCommand.Tax(TaxType.IVA, new BigDecimal("19"))))),
                PaymentMethod.CASH, new CreateInvoiceCommand.CashTerms(), null)).invoice();

        var issued = issueInvoice.issue(new IssueInvoiceCommand(invoice.id().value()));

        assertThat(issued.status()).isEqualTo(InvoiceStatus.REJECTED);
        assertThat(provider.transactionActiveOnCall).containsExactly(false);
    }

    @Test
    @DisplayName("control positivo: la sonda sí detecta una transacción cuando la hay")
    void probeDetectsTransactions() {
        transactionTemplate.executeWithoutResult(status -> provider.issue(null));

        assertThat(provider.transactionActiveOnCall).containsExactly(true);
    }

    static final class RecordingProvider implements ElectronicInvoiceProvider {

        final List<Boolean> transactionActiveOnCall = new ArrayList<>();

        @Override
        public IssuanceResult issue(com.facturaflow.invoicing.domain.model.Invoice invoice) {
            transactionActiveOnCall.add(TransactionSynchronizationManager.isActualTransactionActive());
            return new IssuanceResult.Rejected("respuesta del proveedor de prueba");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RecordingProviderConfiguration {

        @Bean
        RecordingProvider recordingProvider() {
            return new RecordingProvider();
        }
    }
}
