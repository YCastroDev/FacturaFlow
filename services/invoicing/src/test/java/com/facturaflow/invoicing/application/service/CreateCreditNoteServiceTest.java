package com.facturaflow.invoicing.application.service;

import static com.facturaflow.invoicing.domain.model.CreditNoteFixtures.credit;
import static com.facturaflow.invoicing.domain.model.CreditNoteFixtures.partialReturn;
import static com.facturaflow.invoicing.domain.model.CreditNoteFixtures.pendingInvoiceToCredit;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.electronicDocument;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.pendingInvoice;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.invoicing.application.exception.CreditNoteReferenceCodeConflictException;
import com.facturaflow.invoicing.application.exception.InvoiceConcurrentModificationException;
import com.facturaflow.invoicing.application.exception.InvoiceNotFoundException;
import com.facturaflow.invoicing.application.port.in.CreateCreditNoteCommand;
import com.facturaflow.invoicing.application.port.in.CreateCreditNoteResult;
import com.facturaflow.invoicing.application.port.out.RequestHash;
import com.facturaflow.invoicing.domain.exception.InvalidCreditNoteException;
import com.facturaflow.invoicing.domain.exception.InvoiceNotCreditableException;
import com.facturaflow.invoicing.domain.model.CreditBalance;
import com.facturaflow.invoicing.domain.model.CreditNote;
import com.facturaflow.invoicing.domain.model.CreditNoteReason;
import com.facturaflow.invoicing.domain.model.CreditNoteStatus;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.domain.model.InvoiceStatus;
import com.facturaflow.invoicing.domain.model.Money;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CreateCreditNoteServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T15:00:00Z"), ZoneId.of("America/Bogota"));

    private final InMemoryInvoiceRepository invoices = new InMemoryInvoiceRepository();
    private final InMemoryCreditNoteRepository creditNotes = new InMemoryCreditNoteRepository(invoices);
    private final CreateCreditNoteService service = new CreateCreditNoteService(invoices, creditNotes, CLOCK);
    private final Invoice invoice = issuedInvoice();

    private Invoice issuedInvoice() {
        Invoice saved = invoices.saveNew(pendingInvoiceToCredit(), new RequestHash("a".repeat(64)));
        saved.markAsIssued(electronicDocument("147475.01"));
        return invoices.save(saved);
    }

    private static CreateCreditNoteCommand partial(String referenceCode, UUID invoiceId, int line, String quantity) {
        return new CreateCreditNoteCommand(invoiceId, referenceCode, CreditNoteReason.PARTIAL_RETURN,
                List.of(new CreateCreditNoteCommand.Line(line, new BigDecimal(quantity))), "Devolución");
    }

    private CreateCreditNoteCommand partial(String referenceCode, int line, String quantity) {
        return partial(referenceCode, invoice.id().value(), line, quantity);
    }

    private CreditBalance currentBalance() {
        Invoice current = invoices.findById(invoice.id()).orElseThrow();
        return CreditBalance.of(current, creditNotes.findByInvoiceId(invoice.id()));
    }

    /** Otra petición que, en ese instante, crea una nota sobre la misma factura por el camino normal. */
    private Runnable competitorCrediting(String referenceCode, String quantity) {
        CreateCreditNoteService other = new CreateCreditNoteService(invoices, creditNotes, CLOCK);
        return () -> other.create(partial(referenceCode, 1, quantity));
    }

    @Test
    @DisplayName("crea la nota PENDING con la fecha de Colombia y sube la versión de la factura")
    void createsPendingNote() {
        CreateCreditNoteResult result = service.create(partial("DEV-1", 1, "1"));

        assertThat(result.created()).isTrue();
        CreditNote note = result.creditNote();
        assertThat(note.status()).isEqualTo(CreditNoteStatus.PENDING);
        assertThat(note.issueDate()).isEqualTo(LocalDate.of(2026, 10, 22));
        assertThat(note.total()).isEqualTo(Money.of("39158.33"));
        assertThat(note.observation()).hasValue("Devolución");
        assertThat(note.version()).contains(0L);
        assertThat(invoices.findById(invoice.id()).orElseThrow().version()).contains(2L);
        assertThat(invoices.findById(invoice.id()).orElseThrow().status()).isEqualTo(InvoiceStatus.ISSUED);
    }

    @Test
    @DisplayName("una anulación total no lleva líneas y acredita todo lo pendiente")
    void createsAnnulment() {
        service.create(partial("DEV-1", 1, "1"));

        CreditNote annulment = service.create(new CreateCreditNoteCommand(invoice.id().value(), "ANU-1",
                CreditNoteReason.TOTAL_ANNULMENT, null, null)).creditNote();

        assertThat(annulment.lines()).extracting(line -> line.quantity().value())
                .containsExactly(new BigDecimal("2.00"), new BigDecimal("1.50"));
        assertThat(currentBalance().hasRemaining()).isFalse();
    }

    @Test
    @DisplayName("repetir la misma petición devuelve la nota existente sin crear otra (aunque cambie el orden de las líneas)")
    void identicalRetryIsReplayed() {
        CreditNote first = service.create(new CreateCreditNoteCommand(invoice.id().value(), "DEV-1",
                CreditNoteReason.PARTIAL_RETURN, List.of(new CreateCreditNoteCommand.Line(1, new BigDecimal("1")),
                new CreateCreditNoteCommand.Line(2, new BigDecimal("0.5"))), " Devolución ")).creditNote();

        CreateCreditNoteResult retry = service.create(new CreateCreditNoteCommand(invoice.id().value(), "DEV-1",
                CreditNoteReason.PARTIAL_RETURN, List.of(new CreateCreditNoteCommand.Line(2, new BigDecimal("0.50")),
                new CreateCreditNoteCommand.Line(1, new BigDecimal("1.00"))), "Devolución"));

        assertThat(retry.created()).isFalse();
        assertThat(retry.creditNote().id()).isEqualTo(first.id());
        assertThat(creditNotes.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("un reintento idéntico se devuelve aunque la nota ya no cupiera hoy (no se revalida)")
    void retryOfAnAlreadyCreatedNoteIsNotRevalidated() {
        service.create(partial("DEV-1", 1, "3"));

        assertThat(service.create(partial("DEV-1", 1, "3")).created()).isFalse();
    }

    @Test
    @DisplayName("el mismo código con otro contenido es un conflicto")
    void sameReferenceWithOtherContentIsConflict() {
        service.create(partial("DEV-1", 1, "1"));

        assertThatThrownBy(() -> service.create(partial("DEV-1", 1, "2")))
                .isInstanceOf(CreditNoteReferenceCodeConflictException.class)
                .hasMessageContaining("DEV-1");
    }

    @Test
    @DisplayName("si otra petición crea el mismo código entre la consulta y el INSERT, se devuelve la ganadora")
    void referenceCodeRaceIsResolvedAsReplay() {
        CreateCreditNoteService other = new CreateCreditNoteService(invoices, creditNotes, CLOCK);
        creditNotes.beforeNextSaveNew(() -> other.create(partial("DEV-1", 1, "1")));

        CreateCreditNoteResult result = service.create(partial("DEV-1", 1, "1"));

        assertThat(result.created()).isFalse();
        assertThat(creditNotes.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("una factura inexistente indica que no existe")
    void unknownInvoice() {
        assertThatThrownBy(() -> service.create(partial("DEV-1", UUID.randomUUID(), 1, "1")))
                .isInstanceOf(InvoiceNotFoundException.class);
    }

    @Test
    @DisplayName("una factura que no está ISSUED no se acredita")
    void pendingInvoiceIsNotCreditable() {
        Invoice pending = invoices.saveNew(pendingInvoice(), new RequestHash("b".repeat(64)));

        assertThatThrownBy(() -> service.create(partial("DEV-1", pending.id().value(), 1, "1")))
                .isInstanceOf(InvoiceNotCreditableException.class);
        assertThat(creditNotes.size()).isZero();
    }

    @Test
    @DisplayName("acreditar más de lo que queda se rechaza con la regla del dominio y no guarda nada")
    void exceedingIsRejected() {
        service.create(partial("DEV-1", 1, "2"));

        assertThatThrownBy(() -> service.create(partial("DEV-2", 1, "2")))
                .isInstanceOf(InvalidCreditNoteException.class)
                .hasMessageContaining("solo quedan 1.00");
        assertThat(creditNotes.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("una nota sin motivo se rechaza como regla de negocio")
    void missingReason() {
        assertThatThrownBy(() -> service.create(new CreateCreditNoteCommand(invoice.id().value(), "DEV-1", null,
                List.of(), null)))
                .isInstanceOf(InvalidCreditNoteException.class)
                .hasMessageContaining("un motivo");
    }

    @Test
    @DisplayName("si otra nota de la misma factura se guarda entre la lectura y el INSERT, se revalida y se guarda si aún cabe")
    void concurrentNoteThatStillFitsIsRetried() {
        creditNotes.beforeNextSaveNew(competitorCrediting("DEV-OTRA", "1"));

        CreateCreditNoteResult result = service.create(partial("DEV-1", 1, "2"));

        assertThat(result.created()).isTrue();
        assertThat(creditNotes.saveNewAttempts()).isEqualTo(3);   // la otra + nuestro intento fallido + el bueno
        assertThat(currentBalance().line(1).remaining()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("si la nota simultánea consumió el saldo, al revalidar ya no cabe: 422 y nada se guarda")
    void concurrentNoteThatConsumesTheBalanceGives422() {
        creditNotes.beforeNextSaveNew(competitorCrediting("DEV-OTRA", "2"));

        assertThatThrownBy(() -> service.create(partial("DEV-1", 1, "2")))
                .isInstanceOf(InvalidCreditNoteException.class)
                .hasMessageContaining("solo quedan 1.00");
        assertThat(creditNotes.findByInvoiceId(invoice.id())).extracting(note -> note.referenceCode().value())
                .containsExactly("DEV-OTRA");
        assertThat(currentBalance().line(1).credited()).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("si los conflictos persisten en los " + CreateCreditNoteService.MAX_ATTEMPTS
            + " intentos, se propaga el conflicto (409) sin guardar la nota")
    void persistentConflictsGive409() {
        // Un competidor que, antes de cada INSERT, cambia la versión de la factura (p. ej. otras notas).
        creditNotes.beforeEachSaveNew(() -> invoices.incrementVersion(invoice.id(),
                invoices.findById(invoice.id()).orElseThrow().version().orElseThrow()));

        assertThatThrownBy(() -> service.create(partial("DEV-1", 1, "1")))
                .isInstanceOf(InvoiceConcurrentModificationException.class);
        assertThat(creditNotes.saveNewAttempts()).isEqualTo(CreateCreditNoteService.MAX_ATTEMPTS);
        assertThat(creditNotes.size()).isZero();
    }

    @Test
    @DisplayName("una nota REJECTED libera sus cantidades para una nota nueva")
    void rejectedNoteFreesItsQuantities() {
        CreditNote first = service.create(partial("DEV-1", 1, "3")).creditNote();
        first.markAsRejected("rechazo");
        creditNotes.save(first);

        assertThat(service.create(partial("DEV-2", 1, "3")).created()).isTrue();
    }

    @Test
    @DisplayName("la regla también considera las notas creadas por otros caminos (p. ej. datos existentes)")
    void considersExistingNotes() {
        creditNotes.insert(partialReturn(currentBalance(), "DEV-PREVIA", credit(1, "3")));

        assertThatThrownBy(() -> service.create(partial("DEV-1", 1, "1")))
                .isInstanceOf(InvalidCreditNoteException.class);
    }
}
