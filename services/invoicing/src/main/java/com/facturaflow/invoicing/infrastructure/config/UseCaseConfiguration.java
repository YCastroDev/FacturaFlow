package com.facturaflow.invoicing.infrastructure.config;

import com.facturaflow.invoicing.application.port.in.CreateCreditNoteUseCase;
import com.facturaflow.invoicing.application.port.in.CreateInvoiceUseCase;
import com.facturaflow.invoicing.application.port.in.GetCreditBalanceUseCase;
import com.facturaflow.invoicing.application.port.in.GetCreditNotePdfUseCase;
import com.facturaflow.invoicing.application.port.in.GetCreditNoteUseCase;
import com.facturaflow.invoicing.application.port.in.GetInvoicePdfUseCase;
import com.facturaflow.invoicing.application.port.in.GetInvoiceUseCase;
import com.facturaflow.invoicing.application.port.in.IssueCreditNoteUseCase;
import com.facturaflow.invoicing.application.port.in.IssueInvoiceUseCase;
import com.facturaflow.invoicing.application.port.out.CreditNoteRepository;
import com.facturaflow.invoicing.application.port.out.ElectronicCreditNoteProvider;
import com.facturaflow.invoicing.application.port.out.ElectronicInvoiceProvider;
import com.facturaflow.invoicing.application.port.out.InvoiceRepository;
import com.facturaflow.invoicing.application.service.CreateCreditNoteService;
import com.facturaflow.invoicing.application.service.CreateInvoiceService;
import com.facturaflow.invoicing.application.service.GetCreditBalanceService;
import com.facturaflow.invoicing.application.service.GetCreditNotePdfService;
import com.facturaflow.invoicing.application.service.GetCreditNoteService;
import com.facturaflow.invoicing.application.service.GetInvoicePdfService;
import com.facturaflow.invoicing.application.service.GetInvoiceService;
import com.facturaflow.invoicing.application.service.IssueCreditNoteService;
import com.facturaflow.invoicing.application.service.IssueInvoiceService;
import java.time.Clock;
import com.facturaflow.invoicing.application.port.in.GetOutboxStatusUseCase;
import com.facturaflow.invoicing.application.port.in.PublishPendingEventsUseCase;
import com.facturaflow.invoicing.application.port.in.RejectInvoiceRequestUseCase;
import com.facturaflow.invoicing.application.port.out.EventPublisher;
import com.facturaflow.invoicing.application.port.out.InvoiceEventStore;
import com.facturaflow.invoicing.application.port.out.OutboxRepository;
import com.facturaflow.invoicing.application.service.GetOutboxStatusService;
import com.facturaflow.invoicing.application.service.PublishPendingEventsService;
import com.facturaflow.invoicing.application.service.RejectInvoiceRequestService;
import org.springframework.beans.factory.annotation.Value;
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

    // ----- Notas crédito (ADR 0009) -----

    @Bean
    CreateCreditNoteUseCase createCreditNoteUseCase(InvoiceRepository invoices, CreditNoteRepository creditNotes,
                                                    Clock clock) {
        return new CreateCreditNoteService(invoices, creditNotes, clock);
    }

    @Bean
    GetCreditNoteUseCase getCreditNoteUseCase(InvoiceRepository invoices, CreditNoteRepository creditNotes) {
        return new GetCreditNoteService(invoices, creditNotes);
    }

    @Bean
    IssueCreditNoteUseCase issueCreditNoteUseCase(InvoiceRepository invoices, CreditNoteRepository creditNotes,
                                                  ElectronicCreditNoteProvider provider) {
        return new IssueCreditNoteService(invoices, creditNotes, provider);
    }

    @Bean
    GetCreditNotePdfUseCase getCreditNotePdfUseCase(CreditNoteRepository creditNotes,
                                                    ElectronicCreditNoteProvider provider) {
        return new GetCreditNotePdfService(creditNotes, provider);
    }

    @Bean
    GetCreditBalanceUseCase getCreditBalanceUseCase(InvoiceRepository invoices, CreditNoteRepository creditNotes) {
        return new GetCreditBalanceService(invoices, creditNotes);
    }

    // ----- Eventos de facturación (outbox, ADR 0015) -----

    @Bean
    RejectInvoiceRequestUseCase rejectInvoiceRequestUseCase(InvoiceEventStore events) {
        return new RejectInvoiceRequestService(events);
    }

    @Bean
    PublishPendingEventsUseCase publishPendingEventsUseCase(OutboxRepository outbox, EventPublisher publisher,
                                                            @Value("${invoicing.outbox.batch-size}") int batchSize) {
        return new PublishPendingEventsService(outbox, publisher, batchSize);
    }

    @Bean
    GetOutboxStatusUseCase getOutboxStatusUseCase(OutboxRepository outbox) {
        return new GetOutboxStatusService(outbox);
    }
}
