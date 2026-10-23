package com.facturaflow.invoicing.domain.model;

import static com.facturaflow.invoicing.domain.model.CreditNoteFixtures.NOTE_DATE;
import static com.facturaflow.invoicing.domain.model.CreditNoteFixtures.annulment;
import static com.facturaflow.invoicing.domain.model.CreditNoteFixtures.credit;
import static com.facturaflow.invoicing.domain.model.CreditNoteFixtures.creditNoteDocument;
import static com.facturaflow.invoicing.domain.model.CreditNoteFixtures.issuedInvoiceToCredit;
import static com.facturaflow.invoicing.domain.model.CreditNoteFixtures.partialReturn;
import static com.facturaflow.invoicing.domain.model.CreditNoteFixtures.pendingInvoiceToCredit;
import static com.facturaflow.invoicing.domain.model.CreditNoteFixtures.withStatus;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.ISSUE_DATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.facturaflow.invoicing.domain.exception.InvalidCreditNoteException;
import com.facturaflow.invoicing.domain.exception.InvalidQuantityException;
import com.facturaflow.invoicing.domain.exception.InvoiceNotCreditableException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CreditNoteTest {

    private final Invoice invoice = issuedInvoiceToCredit();

    private CreditBalance balance(CreditNote... notes) {
        return CreditBalance.of(invoice, List.of(notes));
    }

    @Nested
    @DisplayName("devolución parcial")
    class PartialReturn {

        @Test
        @DisplayName("acredita una sola línea con menos cantidad que la facturada, y nace PENDING")
        void creditsPartOfOneLine() {
            CreditNote note = partialReturn(balance(), "DEV-1", credit(1, "1"));

            assertThat(note.status()).isEqualTo(CreditNoteStatus.PENDING);
            assertThat(note.invoiceId()).isEqualTo(invoice.id());
            assertThat(note.reason()).isEqualTo(CreditNoteReason.PARTIAL_RETURN);
            assertThat(note.lines()).singleElement().satisfies(line -> {
                assertThat(line.invoiceLineNumber()).isEqualTo(1);
                assertThat(line.quantity()).isEqualTo(Quantity.of("1"));
                assertThat(line.creditedLine().code()).isEqualTo("SKU-RND");
            });
            assertThat(note.version()).isEmpty();
        }

        @Test
        @DisplayName("sus totales salen del mismo cálculo y redondeo que la factura (los mismos que dio Factus en el spike)")
        void totalsUseInvoiceRounding() {
            CreditNote note = partialReturn(balance(), "DEV-1", credit(1, "1"));

            assertThat(note.subtotal()).isEqualTo(Money.of("33333.33"));
            assertThat(note.totalDiscount()).isEqualTo(Money.of("2500.00"));
            assertThat(note.totalTax(TaxType.IVA)).isEqualTo(Money.of("5858.33"));
            assertThat(note.totalTax(TaxType.INC)).isEqualTo(Money.of("2466.67"));
            assertThat(note.totalTax()).isEqualTo(Money.of("8325.00"));
            assertThat(note.total()).isEqualTo(Money.of("39158.33"));
        }

        @Test
        @DisplayName("las líneas quedan ordenadas por número de línea de la factura, sin importar el orden pedido")
        void linesAreSortedByInvoiceLineNumber() {
            CreditNote note = partialReturn(balance(), "DEV-1", credit(2, "0.5"), credit(1, "2"));

            assertThat(note.lines()).extracting(CreditNoteLine::invoiceLineNumber).containsExactly(1, 2);
        }

        @Test
        @DisplayName("puede acreditar exactamente todo lo que queda de una línea")
        void creditsExactlyTheRemaining() {
            CreditNote note = partialReturn(balance(), "DEV-1", credit(1, "3"));

            assertThat(note.lines().getFirst().creditedLine().total()).isEqualTo(Money.of("117474.99"));
        }

        @Test
        @DisplayName("no puede acreditar más de lo facturado en una línea")
        void rejectsMoreThanInvoiced() {
            assertThatThrownBy(() -> partialReturn(balance(), "DEV-1", credit(1, "3.01")))
                    .isInstanceOf(InvalidCreditNoteException.class)
                    .hasMessageContaining("línea 1")
                    .hasMessageContaining("solo quedan 3.00");
        }

        @Test
        @DisplayName("lo acreditado se acumula entre notas: no puede superar lo que queda después de las anteriores")
        void accumulatesAcrossNotes() {
            CreditNote first = partialReturn(balance(), "DEV-1", credit(1, "1"));

            assertThat(partialReturn(balance(first), "DEV-2", credit(1, "2")).total()).isEqualTo(Money.of("78316.66"));
            assertThatThrownBy(() -> partialReturn(balance(first), "DEV-2", credit(1, "2.01")))
                    .isInstanceOf(InvalidCreditNoteException.class)
                    .hasMessageContaining("solo quedan 2.00");
        }

        @Test
        @DisplayName("una nota PENDING cuenta para el saldo: aún puede emitirse")
        void pendingNotesCount() {
            CreditNote pending = partialReturn(balance(), "DEV-1", credit(1, "3"));

            assertThatThrownBy(() -> partialReturn(balance(pending), "DEV-2", credit(1, "1")))
                    .isInstanceOf(InvalidCreditNoteException.class)
                    .hasMessageContaining("solo quedan 0.00");
        }

        @Test
        @DisplayName("una nota ISSUED cuenta para el saldo")
        void issuedNotesCount() {
            CreditNote issued = withStatus(partialReturn(balance(), "DEV-1", credit(1, "3")), CreditNoteStatus.ISSUED);

            assertThatThrownBy(() -> partialReturn(balance(issued), "DEV-2", credit(1, "1")))
                    .isInstanceOf(InvalidCreditNoteException.class);
        }

        @Test
        @DisplayName("una nota REJECTED no cuenta: sus cantidades quedan libres")
        void rejectedNotesDoNotCount() {
            CreditNote rejected = withStatus(partialReturn(balance(), "DEV-1", credit(1, "3")),
                    CreditNoteStatus.REJECTED);

            assertThat(partialReturn(balance(rejected), "DEV-2", credit(1, "3")).lines()).hasSize(1);
        }

        @Test
        @DisplayName("debe acreditar al menos una línea")
        void requiresLines() {
            assertThatThrownBy(() -> partialReturn(balance(), "DEV-1"))
                    .isInstanceOf(InvalidCreditNoteException.class)
                    .hasMessageContaining("al menos una línea");
        }

        @Test
        @DisplayName("no puede repetir una línea")
        void rejectsDuplicateLines() {
            assertThatThrownBy(() -> partialReturn(balance(), "DEV-1", credit(1, "1"), credit(1, "1")))
                    .isInstanceOf(InvalidCreditNoteException.class)
                    .hasMessageContaining("más de una vez");
        }

        @Test
        @DisplayName("no puede acreditar una línea que la factura no tiene")
        void rejectsUnknownLine() {
            assertThatThrownBy(() -> partialReturn(balance(), "DEV-1", credit(3, "1")))
                    .isInstanceOf(InvalidCreditNoteException.class)
                    .hasMessageContaining("no tiene línea 3 (tiene 2)");
        }

        @Test
        @DisplayName("las cantidades deben ser mayores que cero y los números de línea empiezan en 1")
        void rejectsNonPositiveQuantitiesAndLineNumbers() {
            assertThatThrownBy(() -> credit(1, "0")).isInstanceOf(InvalidQuantityException.class);
            assertThatThrownBy(() -> credit(0, "1"))
                    .isInstanceOf(InvalidCreditNoteException.class)
                    .hasMessageContaining("1 o mayor");
        }
    }

    @Nested
    @DisplayName("anulación total")
    class TotalAnnulment {

        @Test
        @DisplayName("sin notas previas acredita toda la factura")
        void creditsWholeInvoice() {
            CreditNote note = annulment(balance(), "ANU-1");

            assertThat(note.lines()).extracting(line -> line.quantity().value())
                    .containsExactly(new BigDecimal("3.00"), new BigDecimal("1.50"));
            assertThat(note.total()).isEqualTo(invoice.total());
        }

        @Test
        @DisplayName("tras una devolución parcial acredita solo lo pendiente")
        void creditsOnlyTheRemaining() {
            CreditNote partial = partialReturn(balance(), "DEV-1", credit(1, "1"));

            CreditNote note = annulment(balance(partial), "ANU-1");

            assertThat(note.lines()).extracting(CreditNoteLine::invoiceLineNumber, line -> line.quantity().value())
                    .containsExactly(tuple(1, new BigDecimal("2.00")), tuple(2, new BigDecimal("1.50")));
        }

        @Test
        @DisplayName("omite las líneas ya acreditadas por completo")
        void skipsFullyCreditedLines() {
            CreditNote partial = partialReturn(balance(), "DEV-1", credit(2, "1.5"));

            assertThat(annulment(balance(partial), "ANU-1").lines())
                    .extracting(CreditNoteLine::invoiceLineNumber).containsExactly(1);
        }

        @Test
        @DisplayName("si ya no queda nada por acreditar, se rechaza")
        void rejectsWhenNothingRemains() {
            CreditNote all = annulment(balance(), "ANU-1");

            assertThatThrownBy(() -> annulment(balance(all), "ANU-2"))
                    .isInstanceOf(InvalidCreditNoteException.class)
                    .hasMessageContaining("ya no tiene cantidades por acreditar");
        }

        @Test
        @DisplayName("no lleva líneas: las decide el saldo de la factura")
        void rejectsExplicitLines() {
            assertThatThrownBy(() -> CreditNote.create(CreditNoteId.generate(), new ReferenceCode("ANU-1"), balance(),
                    CreditNoteReason.TOTAL_ANNULMENT, List.of(credit(1, "1")), NOTE_DATE, null))
                    .isInstanceOf(InvalidCreditNoteException.class)
                    .hasMessageContaining("no lleva líneas");
        }
    }

    @Nested
    @DisplayName("reglas sobre la factura")
    class InvoiceRules {

        @Test
        @DisplayName("solo se acreditan facturas ISSUED: una PENDING es un conflicto con su estado")
        void rejectsPendingInvoice() {
            CreditBalance pendingBalance = CreditBalance.of(pendingInvoiceToCredit(), List.of());

            assertThatThrownBy(() -> partialReturn(pendingBalance, "DEV-1", credit(1, "1")))
                    .isInstanceOf(InvoiceNotCreditableException.class)
                    .hasMessageContaining("PENDING");
        }

        @Test
        @DisplayName("una factura REJECTED tampoco se acredita")
        void rejectsRejectedInvoice() {
            Invoice rejected = pendingInvoiceToCredit();
            rejected.markAsRejected("motivo");

            assertThatThrownBy(() -> annulment(CreditBalance.of(rejected, List.of()), "ANU-1"))
                    .isInstanceOf(InvoiceNotCreditableException.class);
        }

        @Test
        @DisplayName("la nota no puede tener una fecha anterior a la de su factura")
        void rejectsDateBeforeInvoice() {
            assertThatThrownBy(() -> CreditNote.create(CreditNoteId.generate(), new ReferenceCode("DEV-1"), balance(),
                    CreditNoteReason.PARTIAL_RETURN, List.of(credit(1, "1")), ISSUE_DATE.minusDays(1), null))
                    .isInstanceOf(InvalidCreditNoteException.class)
                    .hasMessageContaining("anterior a la de su factura");
        }
    }

    @Nested
    @DisplayName("notas creadas al mismo tiempo")
    class Concurrency {

        /**
         * Dos peticiones leen la misma foto (ninguna ve a la otra) y cada nota, sola, cabe. Juntas
         * acreditarían 4 de 3. El dominio no puede impedirlo con una foto vieja: por eso la
         * persistencia solo inserta si la foto sigue vigente (versión de la factura), y el caso de
         * uso vuelve a validar contra la foto nueva. Este test fija lo que esa revalidación decide.
         */
        @Test
        @DisplayName("dos notas válidas contra la misma foto: al revalidar con la otra incluida, la segunda ya no cabe")
        void secondNoteFailsWhenRevalidatedWithTheFirst() {
            CreditBalance snapshot = balance();
            CreditNote first = partialReturn(snapshot, "DEV-A", credit(1, "2"));
            CreditNote secondOnStaleSnapshot = partialReturn(snapshot, "DEV-B", credit(1, "2"));
            assertThat(secondOnStaleSnapshot.lines()).hasSize(1);   // sola, con la foto vieja, parecía válida

            assertThatThrownBy(() -> partialReturn(balance(first), "DEV-B", credit(1, "2")))
                    .isInstanceOf(InvalidCreditNoteException.class)
                    .hasMessageContaining("solo quedan 1.00");
            assertThat(partialReturn(balance(first), "DEV-B", credit(1, "1")).lines()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("ciclo de vida")
    class Lifecycle {

        private final CreditNote note = partialReturn(balance(), "DEV-1", credit(1, "1"));

        @Test
        @DisplayName("PENDING pasa a ISSUED con su documento electrónico (CUDE)")
        void issues() {
            ElectronicDocument document = creditNoteDocument(Money.of("39158.33"));

            note.markAsIssued(document);

            assertThat(note.status()).isEqualTo(CreditNoteStatus.ISSUED);
            assertThat(note.electronicDocument()).contains(document);
            assertThat(note.hasOfficialTotalDiscrepancy()).isFalse();
        }

        @Test
        @DisplayName("si el total oficial difiere del calculado, se marca la discrepancia")
        void detectsOfficialTotalDiscrepancy() {
            note.markAsIssued(creditNoteDocument(Money.of("39158.34")));

            assertThat(note.hasOfficialTotalDiscrepancy()).isTrue();
        }

        @Test
        @DisplayName("PENDING pasa a REJECTED con su motivo")
        void rejects() {
            note.markAsRejected("  motivo  ");

            assertThat(note.status()).isEqualTo(CreditNoteStatus.REJECTED);
            assertThat(note.rejectionReason()).hasValue("motivo");
        }

        @Test
        @DisplayName("los estados finales no cambian")
        void finalStatesDoNotChange() {
            note.markAsRejected("motivo");

            assertThatThrownBy(() -> note.markAsIssued(creditNoteDocument(Money.of("1.00"))))
                    .isInstanceOf(InvalidCreditNoteException.class)
                    .hasMessageContaining("REJECTED");
        }

        @Test
        @DisplayName("un rechazo exige motivo")
        void rejectionRequiresReason() {
            assertThatThrownBy(() -> note.markAsRejected(" ")).isInstanceOf(InvalidCreditNoteException.class);
        }
    }

    @Nested
    @DisplayName("reconstrucción")
    class Reconstitute {

        private final CreditNote note = partialReturn(balance(), "DEV-1", credit(1, "1"));

        private CreditNote reconstitute(CreditNoteStatus status, ElectronicDocument document, String reason) {
            return CreditNote.reconstitute(note.id(), note.referenceCode(), note.invoiceId(), note.reason(),
                    note.lines(), note.issueDate(), null, status, document, reason, 3L);
        }

        @Test
        @DisplayName("una nota guardada se reconstruye igual, con su versión")
        void rebuildsSameNote() {
            CreditNote rebuilt = reconstitute(CreditNoteStatus.PENDING, null, null);

            assertThat(rebuilt).usingRecursiveComparison().ignoringFields("version").isEqualTo(note);
            assertThat(rebuilt.version()).contains(3L);
        }

        @Test
        @DisplayName("rechaza estados incoherentes, igual que la factura")
        void rejectsInconsistentStates() {
            ElectronicDocument document = creditNoteDocument(Money.of("39158.33"));

            assertThatThrownBy(() -> reconstitute(CreditNoteStatus.PENDING, document, null))
                    .isInstanceOf(InvalidCreditNoteException.class).hasMessageContaining("PENDING");
            assertThatThrownBy(() -> reconstitute(CreditNoteStatus.ISSUED, null, null))
                    .isInstanceOf(InvalidCreditNoteException.class).hasMessageContaining("ISSUED");
            assertThatThrownBy(() -> reconstitute(CreditNoteStatus.REJECTED, document, "motivo"))
                    .isInstanceOf(InvalidCreditNoteException.class).hasMessageContaining("REJECTED");
        }

        @Test
        @DisplayName("rechaza una nota sin líneas o con líneas repetidas")
        void rejectsInvalidLines() {
            assertThatThrownBy(() -> CreditNote.reconstitute(note.id(), note.referenceCode(), note.invoiceId(),
                    note.reason(), List.of(), note.issueDate(), null, CreditNoteStatus.PENDING, null, null, 0L))
                    .isInstanceOf(InvalidCreditNoteException.class);
            CreditNoteLine line = note.lines().getFirst();
            assertThatThrownBy(() -> CreditNote.reconstitute(note.id(), note.referenceCode(), note.invoiceId(),
                    note.reason(), List.of(line, line), note.issueDate(), null, CreditNoteStatus.PENDING, null, null,
                    0L))
                    .isInstanceOf(InvalidCreditNoteException.class).hasMessageContaining("más de una vez");
        }
    }

    @Test
    @DisplayName("la observación se guarda sin espacios, vacía es null y admite máximo 250 caracteres")
    void normalizesObservation() {
        assertThat(CreditNote.normalizedObservation("  hola ")).isEqualTo("hola");
        assertThat(CreditNote.normalizedObservation("   ")).isNull();
        assertThatThrownBy(() -> CreditNote.normalizedObservation("x".repeat(251)))
                .isInstanceOf(InvalidCreditNoteException.class)
                .hasMessageContaining("250");
    }
}
