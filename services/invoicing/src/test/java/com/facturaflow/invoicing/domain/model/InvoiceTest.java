package com.facturaflow.invoicing.domain.model;

import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.INVOICE_ID;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.ISSUE_DATE;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.customer;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.electronicDocument;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.excludedLine;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.invoiceWith;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.line;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.pendingInvoice;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.invoicing.domain.exception.InvalidInvoiceException;
import com.facturaflow.invoicing.domain.exception.InvalidInvoiceStateTransitionException;
import com.facturaflow.invoicing.domain.exception.InvalidPaymentTermsException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class InvoiceTest {

    private static Invoice create(PaymentTerms terms, String observation) {
        return Invoice.create(INVOICE_ID, new ReferenceCode("SALE-0001"), customer(),
                List.of(line("1", "1000", "0", "19")), PaymentMethod.BANK_TRANSFER, terms, ISSUE_DATE, observation);
    }

    @Nested
    @DisplayName("Creación")
    class Creation {

        @Test
        @DisplayName("una factura válida nace pendiente, sin documento electrónico ni motivo de rechazo")
        void validInvoiceStartsPending() {
            Invoice invoice = pendingInvoice();

            assertThat(invoice.status()).isEqualTo(InvoiceStatus.PENDING);
            assertThat(invoice.electronicDocument()).isEmpty();
            assertThat(invoice.rejectionReason()).isEmpty();
            assertThat(invoice.issueDate()).isEqualTo(ISSUE_DATE);
        }

        @Test
        @DisplayName("las líneas de la factura no se pueden modificar desde afuera")
        void linesAreImmutable() {
            List<InvoiceLine> original = new ArrayList<>(List.of(line("1", "1000", "0", "19")));
            Invoice invoice = invoiceWith(original);

            original.add(line("1", "5", "0", "0"));

            assertThat(invoice.lines()).hasSize(1);
            assertThatThrownBy(() -> invoice.lines().add(line("1", "5", "0", "0")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("una factura sin líneas se rechaza")
        void rejectsInvoiceWithoutLines() {
            assertThatThrownBy(() -> invoiceWith(List.of()))
                    .isInstanceOf(InvalidInvoiceException.class)
                    .hasMessageContaining("al menos una línea");
            assertThatThrownBy(() -> invoiceWith(null)).isInstanceOf(InvalidInvoiceException.class);
        }

        @Test
        @DisplayName("una factura sin cliente, medio de pago o fecha de emisión se rechaza")
        void rejectsMissingRequiredData() {
            List<InvoiceLine> lines = List.of(line("1", "1000", "0", "19"));
            ReferenceCode ref = new ReferenceCode("SALE-1");

            assertThatThrownBy(() -> Invoice.create(INVOICE_ID, ref, null, lines, PaymentMethod.CASH,
                    PaymentTerms.cash(), ISSUE_DATE, null)).hasMessageContaining("cliente");
            assertThatThrownBy(() -> Invoice.create(INVOICE_ID, ref, customer(), lines, null,
                    PaymentTerms.cash(), ISSUE_DATE, null)).hasMessageContaining("medio de pago");
            assertThatThrownBy(() -> Invoice.create(INVOICE_ID, ref, customer(), lines, PaymentMethod.CASH,
                    PaymentTerms.cash(), null, null)).hasMessageContaining("fecha de emisión");
        }

        @Test
        @DisplayName("una venta a crédito con vencimiento posterior a la emisión es válida")
        void acceptsCreditWithLaterDueDate() {
            Invoice invoice = create(PaymentTerms.credit(ISSUE_DATE.plusDays(30)), null);

            assertThat(invoice.paymentTerms()).isEqualTo(new PaymentTerms.Credit(LocalDate.of(2026, 11, 19)));
        }

        @ParameterizedTest(name = "vencimiento {0} días después de la emisión")
        @ValueSource(ints = {0, -1, -30})
        @DisplayName("una venta a crédito exige vencimiento posterior a la fecha de emisión")
        void rejectsCreditDueOnOrBeforeIssueDate(int daysAfterIssue) {
            assertThatThrownBy(() -> create(PaymentTerms.credit(ISSUE_DATE.plusDays(daysAfterIssue)), null))
                    .isInstanceOf(InvalidPaymentTermsException.class)
                    .hasMessageContaining("posterior");
        }

        @Test
        @DisplayName("una venta a crédito sin fecha de vencimiento se rechaza")
        void rejectsCreditWithoutDueDate() {
            assertThatThrownBy(() -> PaymentTerms.credit(null))
                    .isInstanceOf(InvalidPaymentTermsException.class)
                    .hasMessageContaining("fecha de vencimiento");
        }

        @Test
        @DisplayName("la observación es opcional y admite hasta 250 caracteres")
        void observationIsOptionalUpTo250() {
            assertThat(create(PaymentTerms.cash(), null).observation()).isEmpty();
            assertThat(create(PaymentTerms.cash(), "   ").observation()).isEmpty();
            assertThat(create(PaymentTerms.cash(), "x".repeat(250)).observation()).hasValue("x".repeat(250));
        }

        @Test
        @DisplayName("una observación de más de 250 caracteres se rechaza")
        void rejectsObservationLongerThan250() {
            assertThatThrownBy(() -> create(PaymentTerms.cash(), "x".repeat(251)))
                    .isInstanceOf(InvalidInvoiceException.class)
                    .hasMessageContaining("250");
        }

        @Test
        @DisplayName("dos facturas son la misma si tienen el mismo id, aunque cambie su contenido")
        void equalityIsById() {
            Invoice a = invoiceWith(List.of(line("1", "1000", "0", "19")));
            Invoice b = invoiceWith(List.of(line("9", "5", "0", "0")));
            Invoice other = Invoice.create(InvoiceId.generate(), new ReferenceCode("SALE-0001"), customer(),
                    List.of(line("1", "1000", "0", "19")), PaymentMethod.CASH, PaymentTerms.cash(), ISSUE_DATE, null);

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b).isNotEqualTo(other);
        }
    }

    @Nested
    @DisplayName("Totales")
    class Totals {

        @Test
        @DisplayName("los totales suman descuentos e impuestos de todas las líneas, incluida una excluida")
        void computesTotals() {
            // A: 2 x 50.000 - 10% = 90.000 + 19% (17.100)            = 107.100,00
            // B: 1 x 20.000 excluida                                  =  20.000,00
            // C: 3 x 1.999,99 = 5.999,97 - 5% (300,00) + 5% (285,00)  =   5.984,97
            Invoice invoice = invoiceWith(List.of(
                    line("2", "50000.00", "10", "19"),
                    excludedLine("1", "20000.00"),
                    line("3", "1999.99", "5", "5")));

            assertThat(invoice.subtotal()).isEqualTo(Money.of("125999.97"));
            assertThat(invoice.totalDiscount()).isEqualTo(Money.of("10300.00"));
            assertThat(invoice.totalTax()).isEqualTo(Money.of("17385.00"));
            assertThat(invoice.total()).isEqualTo(Money.of("133084.97"));
        }

        @Test
        @DisplayName("el impuesto total se desglosa por tipo y el desglose suma el total")
        void breaksDownTaxByType() {
            Invoice invoice = invoiceWith(List.of(
                    line("2", "50000.00", "10", LineTax.of(TaxType.IVA, "19"), LineTax.of(TaxType.INC, "8")),
                    line("1", "1000.00", "0", "5"),
                    excludedLine("1", "20000.00")));

            assertThat(invoice.totalTax(TaxType.IVA)).isEqualTo(Money.of("17150.00"));   // 17.100 + 50
            assertThat(invoice.totalTax(TaxType.INC)).isEqualTo(Money.of("7200.00"));
            assertThat(invoice.totalTax()).isEqualTo(Money.of("24350.00"));
        }

        @Test
        @DisplayName("el total de la factura es exactamente la suma de los totales de sus líneas")
        void totalEqualsSumOfLineTotals() {
            Invoice invoice = invoiceWith(List.of(line("0.33", "0.33", "7", "19"), line("1.5", "333.33", "3", "5")));

            Money sumOfLines = invoice.lines().stream().map(InvoiceLine::total).reduce(Money.ZERO, Money::plus);
            assertThat(invoice.total()).isEqualTo(sumOfLines);
        }

        @Test
        @DisplayName("el impuesto se redondea por línea y la factura suma los valores ya redondeados")
        void taxIsRoundedPerLine() {
            // Cada línea: 0,10 x 5% = 0,005 -> 0,01. Por línea: 0,03. Sobre el total habría sido 0,015 -> 0,02.
            Invoice invoice = invoiceWith(List.of(
                    line("1", "0.10", "0", "5"), line("1", "0.10", "0", "5"), line("1", "0.10", "0", "5")));

            assertThat(invoice.totalTax()).isEqualTo(Money.of("0.03"));
            assertThat(invoice.total()).isEqualTo(Money.of("0.33"));
        }
    }

    @Nested
    @DisplayName("Estados")
    class StateMachine {

        @Test
        @DisplayName("una factura pendiente se emite y guarda el documento electrónico")
        void pendingCanBeIssued() {
            Invoice invoice = pendingInvoice();
            ElectronicDocument document = electronicDocument("119000.00");

            invoice.markAsIssued(document);

            assertThat(invoice.status()).isEqualTo(InvoiceStatus.ISSUED);
            assertThat(invoice.electronicDocument()).contains(document);
        }

        @Test
        @DisplayName("una factura pendiente se rechaza y guarda el motivo")
        void pendingCanBeRejected() {
            Invoice invoice = pendingInvoice();

            invoice.markAsRejected("  Regla FAD06: NIT del adquirente inválido ");

            assertThat(invoice.status()).isEqualTo(InvoiceStatus.REJECTED);
            assertThat(invoice.rejectionReason()).hasValue("Regla FAD06: NIT del adquirente inválido");
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({"ISSUED, ISSUED", "ISSUED, REJECTED", "REJECTED, ISSUED", "REJECTED, REJECTED"})
        @DisplayName("una factura emitida o rechazada ya no cambia de estado")
        void finalStatesCannotChange(InvoiceStatus from, InvoiceStatus to) {
            Invoice invoice = pendingInvoice();
            moveTo(invoice, from);

            assertThatThrownBy(() -> moveTo(invoice, to))
                    .isInstanceOf(InvalidInvoiceStateTransitionException.class)
                    .hasMessageContaining(from.name())
                    .hasMessageContaining(to.name());
            assertThat(invoice.status()).isEqualTo(from);
        }

        @ParameterizedTest(name = "{0} -> {1}: {2}")
        @CsvSource({
                "PENDING,  PENDING,  false",
                "PENDING,  ISSUED,   true",
                "PENDING,  REJECTED, true",
                "ISSUED,   PENDING,  false",
                "ISSUED,   ISSUED,   false",
                "ISSUED,   REJECTED, false",
                "REJECTED, PENDING,  false",
                "REJECTED, ISSUED,   false",
                "REJECTED, REJECTED, false"
        })
        @DisplayName("solo una factura pendiente puede pasar a emitida o rechazada")
        void transitionTable(InvoiceStatus from, InvoiceStatus to, boolean allowed) {
            assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
        }

        @Test
        @DisplayName("emitir requiere el documento electrónico")
        void issuingRequiresDocument() {
            Invoice invoice = pendingInvoice();

            assertThatThrownBy(() -> invoice.markAsIssued(null)).isInstanceOf(InvalidInvoiceException.class);
            assertThat(invoice.status()).isEqualTo(InvoiceStatus.PENDING);
        }

        @ParameterizedTest(name = "motivo ''{0}''")
        @ValueSource(strings = {"", "   "})
        @DisplayName("rechazar requiere un motivo")
        void rejectingRequiresReason(String reason) {
            Invoice invoice = pendingInvoice();

            assertThatThrownBy(() -> invoice.markAsRejected(reason))
                    .isInstanceOf(InvalidInvoiceException.class)
                    .hasMessageContaining("motivo");
            assertThat(invoice.status()).isEqualTo(InvoiceStatus.PENDING);
        }

        private static void moveTo(Invoice invoice, InvoiceStatus target) {
            switch (target) {
                case ISSUED -> invoice.markAsIssued(electronicDocument("119000.00"));
                case REJECTED -> invoice.markAsRejected("motivo de prueba");
                case PENDING -> throw new IllegalArgumentException("PENDING no es un destino");
            }
        }
    }

    @Nested
    @DisplayName("Total oficial (decisión B)")
    class OfficialTotal {

        @Test
        @DisplayName("si el total oficial coincide con el calculado no hay discrepancia")
        void noDiscrepancyWhenTotalsMatch() {
            Invoice invoice = pendingInvoice();   // 100.000 + 19% = 119.000

            invoice.markAsIssued(electronicDocument("119000.00"));

            assertThat(invoice.hasOfficialTotalDiscrepancy()).isFalse();
        }

        @Test
        @DisplayName("si el total oficial difiere, se guarda igual (es el legal) y se señala la discrepancia")
        void keepsOfficialTotalAndFlagsDiscrepancy() {
            Invoice invoice = pendingInvoice();

            invoice.markAsIssued(electronicDocument("119000.01"));

            assertThat(invoice.status()).isEqualTo(InvoiceStatus.ISSUED);
            assertThat(invoice.electronicDocument()).get()
                    .extracting(ElectronicDocument::officialTotal).isEqualTo(Money.of("119000.01"));
            assertThat(invoice.total()).isEqualTo(Money.of("119000.00"));
            assertThat(invoice.hasOfficialTotalDiscrepancy()).isTrue();
        }

        @Test
        @DisplayName("una factura no emitida no tiene discrepancia de totales")
        void pendingInvoiceHasNoDiscrepancy() {
            assertThat(pendingInvoice().hasOfficialTotalDiscrepancy()).isFalse();
        }
    }

    @Nested
    @DisplayName("Eventos de dominio")
    class Events {

        @Test
        @DisplayName("una factura nueva no tiene eventos; emitirla registra InvoiceIssued")
        void issuingRegistersInvoiceIssued() {
            Invoice invoice = pendingInvoice();
            assertThat(invoice.pendingEvents()).isEmpty();

            invoice.markAsIssued(electronicDocument("119000.00"));

            assertThat(invoice.pendingEvents()).containsExactly(new InvoiceEvent.InvoiceIssued(INVOICE_ID));
        }

        @Test
        @DisplayName("rechazarla registra InvoiceRejected")
        void rejectingRegistersInvoiceRejected() {
            Invoice invoice = pendingInvoice();

            invoice.markAsRejected("La DIAN no validó el documento");

            assertThat(invoice.pendingEvents()).containsExactly(new InvoiceEvent.InvoiceRejected(INVOICE_ID));
        }

        @Test
        @DisplayName("una transición inválida no registra ningún evento")
        void invalidTransitionRegistersNothing() {
            Invoice invoice = pendingInvoice();
            invoice.markAsRejected("rechazada");

            assertThatThrownBy(() -> invoice.markAsIssued(electronicDocument("119000.00")))
                    .isInstanceOf(InvalidInvoiceStateTransitionException.class);
            assertThat(invoice.pendingEvents()).containsExactly(new InvoiceEvent.InvoiceRejected(INVOICE_ID));
        }

        @Test
        @DisplayName("los eventos pendientes no se pueden modificar desde fuera del agregado")
        void pendingEventsAreACopy() {
            Invoice invoice = pendingInvoice();
            invoice.markAsIssued(electronicDocument("119000.00"));

            assertThatThrownBy(() -> invoice.pendingEvents().clear()).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("una petición rechazada exige código de referencia y motivo")
        void requestRejectedRequiresReferenceAndReason() {
            assertThatThrownBy(() -> new InvoiceEvent.InvoiceRequestRejected(" ", null, null, "motivo"))
                    .isInstanceOf(InvalidInvoiceException.class);
            assertThatThrownBy(() -> new InvoiceEvent.InvoiceRequestRejected("VENTA-1", null, null, null))
                    .isInstanceOf(InvalidInvoiceException.class);
        }
    }
}
