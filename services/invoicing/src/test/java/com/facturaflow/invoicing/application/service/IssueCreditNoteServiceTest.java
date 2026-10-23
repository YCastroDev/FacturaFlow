package com.facturaflow.invoicing.application.service;

import static com.facturaflow.invoicing.domain.model.CreditNoteFixtures.credit;
import static com.facturaflow.invoicing.domain.model.CreditNoteFixtures.creditNoteDocument;
import static com.facturaflow.invoicing.domain.model.CreditNoteFixtures.partialReturn;
import static com.facturaflow.invoicing.domain.model.CreditNoteFixtures.pendingInvoiceToCredit;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.electronicDocument;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.invoicing.application.exception.CreditNoteAlreadyRejectedException;
import com.facturaflow.invoicing.application.exception.CreditNoteConcurrentModificationException;
import com.facturaflow.invoicing.application.exception.CreditNoteNotFoundException;
import com.facturaflow.invoicing.application.exception.ElectronicInvoiceProviderUnavailableException;
import com.facturaflow.invoicing.application.port.in.IssueCreditNoteCommand;
import com.facturaflow.invoicing.application.port.out.RequestHash;
import com.facturaflow.invoicing.domain.model.CreditBalance;
import com.facturaflow.invoicing.domain.model.CreditNote;
import com.facturaflow.invoicing.domain.model.CreditNoteStatus;
import com.facturaflow.invoicing.domain.model.ElectronicDocument;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.domain.model.Money;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IssueCreditNoteServiceTest {

    private static final ElectronicDocument DOCUMENT = creditNoteDocument(Money.of("39158.33"));
    private static final ElectronicDocument OTHER_DOCUMENT = new ElectronicDocument("NC1662", "a".repeat(96),
            URI.create("https://dian.example/qr?key=nc2"), URI.create("https://sandbox.example/credit-notes/2"),
            Instant.parse("2026-10-22T20:18:06Z"), Money.of("39158.33"));

    private final InMemoryInvoiceRepository invoices = new InMemoryInvoiceRepository();
    private final InMemoryCreditNoteRepository creditNotes = new InMemoryCreditNoteRepository(invoices);
    private final StubElectronicCreditNoteProvider provider = new StubElectronicCreditNoteProvider();
    private final IssueCreditNoteService service = new IssueCreditNoteService(invoices, creditNotes, provider);
    private final Invoice invoice = issuedInvoice();
    private final CreditNote pending = creditNotes.insert(
            partialReturn(CreditBalance.of(invoice, List.of()), "DEV-1", credit(1, "1")));

    private Invoice issuedInvoice() {
        Invoice saved = invoices.saveNew(pendingInvoiceToCredit(), new RequestHash("a".repeat(64)));
        saved.markAsIssued(electronicDocument("147475.01"));
        return invoices.save(saved);
    }

    private CreditNote issue() {
        return service.issue(new IssueCreditNoteCommand(pending.id().value()));
    }

    private CreditNote stored() {
        return creditNotes.findById(pending.id()).orElseThrow();
    }

    @Test
    @DisplayName("si el proveedor la emite, la nota queda ISSUED con su documento y el proveedor recibe la factura")
    void issued() {
        provider.willIssue(DOCUMENT);

        CreditNote result = issue();

        assertThat(result.status()).isEqualTo(CreditNoteStatus.ISSUED);
        assertThat(result.electronicDocument()).contains(DOCUMENT);
        assertThat(stored().status()).isEqualTo(CreditNoteStatus.ISSUED);
        assertThat(provider.lastInvoice().id()).isEqualTo(invoice.id());
        assertThat(provider.lastInvoice().customer()).isEqualTo(invoice.customer());
    }

    @Test
    @DisplayName("emitir la nota no toca la versión de la factura: no cambia lo acreditado")
    void issuingDoesNotTouchTheInvoice() {
        provider.willIssue(DOCUMENT);

        issue();

        assertThat(invoices.findById(invoice.id()).orElseThrow().version()).isEqualTo(invoice.version());
    }

    @Test
    @DisplayName("si el proveedor la rechaza, la nota queda REJECTED con el motivo: es un resultado, no un error")
    void rejected() {
        provider.willReject("Regla CAJ02: motivo de prueba");

        CreditNote result = issue();

        assertThat(result.status()).isEqualTo(CreditNoteStatus.REJECTED);
        assertThat(result.rejectionReason()).hasValue("Regla CAJ02: motivo de prueba");
    }

    @Test
    @DisplayName("si el proveedor falla técnicamente, la nota sigue PENDING y se puede reintentar")
    void providerUnavailable() {
        provider.willBeUnavailable();

        assertThatThrownBy(this::issue)
                .isInstanceOf(ElectronicInvoiceProviderUnavailableException.class)
                .hasMessageContaining("nota crédito " + pending.id());
        assertThat(stored().status()).isEqualTo(CreditNoteStatus.PENDING);
        assertThat(stored().version()).contains(0L);

        provider.willIssue(DOCUMENT);
        assertThat(issue().status()).isEqualTo(CreditNoteStatus.ISSUED);
    }

    @Test
    @DisplayName("emitir una nota ya ISSUED la devuelve sin volver a llamar al proveedor")
    void alreadyIssuedIsReturnedWithoutCallingProvider() {
        provider.willIssue(DOCUMENT);
        issue();

        assertThat(issue().electronicDocument()).contains(DOCUMENT);
        assertThat(provider.calls()).isEqualTo(1);
    }

    @Test
    @DisplayName("emitir una nota REJECTED es un conflicto y no llama al proveedor")
    void alreadyRejectedIsConflict() {
        provider.willReject("motivo");
        issue();

        assertThatThrownBy(this::issue).isInstanceOf(CreditNoteAlreadyRejectedException.class);
        assertThat(provider.calls()).isEqualTo(1);
    }

    @Test
    @DisplayName("emitir una nota inexistente indica que no existe")
    void unknownCreditNote() {
        assertThatThrownBy(() -> service.issue(new IssueCreditNoteCommand(UUID.randomUUID())))
                .isInstanceOf(CreditNoteNotFoundException.class);
        assertThat(provider.calls()).isZero();
    }

    @Test
    @DisplayName("si otra emisión simultánea la dejó ISSUED, se devuelve la guardada y se descarta el documento propio")
    void concurrentIssueEndingIssuedReturnsStored() {
        StubElectronicCreditNoteProvider otherProvider = new StubElectronicCreditNoteProvider();
        otherProvider.willIssue(OTHER_DOCUMENT);
        IssueCreditNoteService other = new IssueCreditNoteService(invoices, creditNotes, otherProvider);
        provider.willIssue(DOCUMENT);
        provider.whileCalling(() -> other.issue(new IssueCreditNoteCommand(pending.id().value())));

        CreditNote result = issue();

        assertThat(result.electronicDocument()).contains(OTHER_DOCUMENT);
        assertThat(stored().electronicDocument()).contains(OTHER_DOCUMENT);
    }

    @Test
    @DisplayName("si otra emisión simultánea la dejó REJECTED mientras a nosotros nos la emitieron, es un conflicto")
    void concurrentIssueEndingRejectedIsConflict() {
        StubElectronicCreditNoteProvider otherProvider = new StubElectronicCreditNoteProvider();
        otherProvider.willReject("rechazo de la otra petición");
        IssueCreditNoteService other = new IssueCreditNoteService(invoices, creditNotes, otherProvider);
        provider.willIssue(DOCUMENT);
        provider.whileCalling(() -> other.issue(new IssueCreditNoteCommand(pending.id().value())));

        assertThatThrownBy(this::issue).isInstanceOf(CreditNoteConcurrentModificationException.class);
        assertThat(stored().status()).isEqualTo(CreditNoteStatus.REJECTED);
    }
}
