package com.facturaflow.invoicing.application.service;

import static com.facturaflow.invoicing.domain.model.CreditNoteFixtures.credit;
import static com.facturaflow.invoicing.domain.model.CreditNoteFixtures.creditNoteDocument;
import static com.facturaflow.invoicing.domain.model.CreditNoteFixtures.partialReturn;
import static com.facturaflow.invoicing.domain.model.CreditNoteFixtures.pendingInvoiceToCredit;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.electronicDocument;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.pendingInvoice;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.invoicing.application.exception.CreditNoteNotFoundException;
import com.facturaflow.invoicing.application.exception.CreditNoteNotIssuedException;
import com.facturaflow.invoicing.application.exception.InvoiceNotFoundException;
import com.facturaflow.invoicing.application.port.out.InvoicePdf;
import com.facturaflow.invoicing.application.port.out.RequestHash;
import com.facturaflow.invoicing.domain.model.CreditBalance;
import com.facturaflow.invoicing.domain.model.CreditNote;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.domain.model.Money;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Consultas de notas crédito: por id, por factura, PDF y saldo de la factura. */
class CreditNoteQueryServicesTest {

    private final InMemoryInvoiceRepository invoices = new InMemoryInvoiceRepository();
    private final InMemoryCreditNoteRepository creditNotes = new InMemoryCreditNoteRepository(invoices);
    private final StubElectronicCreditNoteProvider provider = new StubElectronicCreditNoteProvider();
    private final GetCreditNoteService get = new GetCreditNoteService(invoices, creditNotes);
    private final GetCreditNotePdfService pdf = new GetCreditNotePdfService(creditNotes, provider);
    private final GetCreditBalanceService balance = new GetCreditBalanceService(invoices, creditNotes);
    private final Invoice invoice = issuedInvoice();
    private final CreditNote note = creditNotes.insert(
            partialReturn(CreditBalance.of(invoice, List.of()), "DEV-1", credit(1, "1")));

    private Invoice issuedInvoice() {
        Invoice saved = invoices.saveNew(pendingInvoiceToCredit(), new RequestHash("a".repeat(64)));
        saved.markAsIssued(electronicDocument("147475.01"));
        return invoices.save(saved);
    }

    @Test
    @DisplayName("una nota se consulta por id; una inexistente indica que no existe")
    void byId() {
        assertThat(get.byId(note.id().value())).isEqualTo(note);
        assertThatThrownBy(() -> get.byId(UUID.randomUUID())).isInstanceOf(CreditNoteNotFoundException.class);
    }

    @Test
    @DisplayName("las notas se listan por factura; una factura sin notas da una lista vacía y una inexistente, 404")
    void byInvoice() {
        Invoice withoutNotes = invoices.saveNew(pendingInvoice(),
                new RequestHash("b".repeat(64)));

        assertThat(get.byInvoice(invoice.id().value())).containsExactly(note);
        assertThat(get.byInvoice(withoutNotes.id().value())).isEmpty();
        assertThatThrownBy(() -> get.byInvoice(UUID.randomUUID())).isInstanceOf(InvoiceNotFoundException.class);
    }

    @Test
    @DisplayName("el PDF solo existe para una nota ISSUED y se pide al proveedor")
    void pdfOnlyWhenIssued() {
        assertThatThrownBy(() -> pdf.pdf(note.id().value()))
                .isInstanceOf(CreditNoteNotIssuedException.class)
                .hasMessageContaining("PENDING");
        assertThat(provider.calls()).isZero();

        CreditNote issued = creditNotes.findById(note.id()).orElseThrow();
        issued.markAsIssued(creditNoteDocument(Money.of("39158.33")));
        creditNotes.save(issued);
        InvoicePdf expected = new InvoicePdf("nc1661", new byte[] {'%'});
        provider.willReturnPdf(expected);

        assertThat(pdf.pdf(note.id().value())).isSameAs(expected);
        assertThatThrownBy(() -> pdf.pdf(UUID.randomUUID())).isInstanceOf(CreditNoteNotFoundException.class);
    }

    @Test
    @DisplayName("el saldo de una factura considera todas sus notas; una factura inexistente indica que no existe")
    void balanceOfInvoice() {
        CreditBalance result = balance.forInvoice(invoice.id().value());

        assertThat(result.pendingCreditTotal()).isEqualTo(Money.of("39158.33"));
        assertThat(result.line(1).remaining()).isEqualByComparingTo("2");
        assertThatThrownBy(() -> balance.forInvoice(UUID.randomUUID())).isInstanceOf(InvoiceNotFoundException.class);
    }
}
