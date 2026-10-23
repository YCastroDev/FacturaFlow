package com.facturaflow.invoicing.domain.model;

import static com.facturaflow.invoicing.domain.model.CreditNoteFixtures.annulment;
import static com.facturaflow.invoicing.domain.model.CreditNoteFixtures.credit;
import static com.facturaflow.invoicing.domain.model.CreditNoteFixtures.issuedInvoiceToCredit;
import static com.facturaflow.invoicing.domain.model.CreditNoteFixtures.partialReturn;
import static com.facturaflow.invoicing.domain.model.CreditNoteFixtures.withStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.facturaflow.invoicing.domain.exception.InvalidCreditNoteException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CreditBalanceTest {

    private final Invoice invoice = issuedInvoiceToCredit();
    private final CreditBalance empty = CreditBalance.of(invoice, List.of());

    @Test
    @DisplayName("sin notas, cada línea tiene pendiente todo lo facturado y el saldo es el total de la factura")
    void withoutNotes() {
        assertThat(empty.lines()).extracting(CreditBalance.LineBalance::lineNumber,
                        line -> line.credited(), line -> line.remaining())
                .containsExactly(tuple(1, new BigDecimal("0.00"), new BigDecimal("3.00")),
                        tuple(2, new BigDecimal("0.00"), new BigDecimal("1.50")));
        assertThat(empty.remainingValue()).isEqualTo(invoice.total());
        assertThat(empty.issuedCreditTotal()).isEqualTo(Money.ZERO);
        assertThat(empty.pendingCreditTotal()).isEqualTo(Money.ZERO);
        assertThat(empty.hasRemaining()).isTrue();
    }

    @Test
    @DisplayName("separa lo acreditado por notas ISSUED de lo reservado por notas PENDING; las REJECTED no cuentan")
    void separatesIssuedPendingAndRejected() {
        CreditNote issued = withStatus(partialReturn(empty, "DEV-1", credit(1, "1")), CreditNoteStatus.ISSUED);
        CreditNote pending = partialReturn(empty, "DEV-2", credit(2, "0.5"));
        CreditNote rejected = withStatus(partialReturn(empty, "DEV-3", credit(1, "2")), CreditNoteStatus.REJECTED);

        CreditBalance balance = CreditBalance.of(invoice, List.of(issued, pending, rejected));

        assertThat(balance.issuedCreditTotal()).isEqualTo(Money.of("39158.33"));
        assertThat(balance.pendingCreditTotal()).isEqualTo(Money.of("10000.01"));
        assertThat(balance.line(1).credited()).isEqualByComparingTo("1");
        assertThat(balance.line(1).remaining()).isEqualByComparingTo("2");
        assertThat(balance.line(2).remaining()).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("el saldo es el valor de lo que queda por acreditar, con el redondeo por línea")
    void remainingValueIsValueOfRemainingQuantities() {
        CreditNote one = partialReturn(empty, "DEV-1", credit(1, "1"));

        CreditBalance balance = CreditBalance.of(invoice, List.of(one));

        // 2 x 33.333,33 con 7,5 % e IVA + INC = 78.316,66; más la línea excluida completa (30.000,02)
        assertThat(balance.remainingValue()).isEqualTo(Money.of("108316.68"));
    }

    @Test
    @DisplayName("acreditar una línea en partes puede sumar centavos de más en dinero: por eso la regla va en cantidades")
    void partialCreditsMayDifferByCentsFromTheLineTotal() {
        // Línea 2: 1,5 x 20.000,01 = 30.000,015 → 30.000,02. En tres partes de 0,5: 10.000,005 → 10.000,01 cada una.
        CreditNote first = partialReturn(empty, "DEV-1", credit(2, "0.5"));
        CreditNote second = partialReturn(CreditBalance.of(invoice, List.of(first)), "DEV-2", credit(2, "0.5"));
        CreditNote third = partialReturn(CreditBalance.of(invoice, List.of(first, second)), "DEV-3", credit(2, "0.5"));

        Money creditedInParts = first.total().plus(second.total()).plus(third.total());

        assertThat(creditedInParts).isEqualTo(Money.of("30000.03"));
        assertThat(invoice.lines().get(1).total()).isEqualTo(Money.of("30000.02"));
        // La regla compara cantidades, que no se redondean: la línea quedó acreditada exactamente una vez.
        assertThat(CreditBalance.of(invoice, List.of(first, second, third)).line(2).hasRemaining()).isFalse();
    }

    @Test
    @DisplayName("una anulación deja la factura sin saldo")
    void annulmentLeavesNothing() {
        CreditBalance balance = CreditBalance.of(invoice, List.of(annulment(empty, "ANU-1")));

        assertThat(balance.hasRemaining()).isFalse();
        assertThat(balance.remainingValue()).isEqualTo(Money.ZERO);
        assertThat(balance.pendingCreditTotal()).isEqualTo(invoice.total());
    }

    @Test
    @DisplayName("no admite notas de otra factura")
    void rejectsForeignNotes() {
        Invoice other = Invoice.reconstitute(InvoiceId.generate(), new ReferenceCode("OTRA"), invoice.customer(),
                invoice.lines(), invoice.paymentMethod(), invoice.paymentTerms(), invoice.issueDate(), null,
                InvoiceStatus.ISSUED, invoice.electronicDocument().orElseThrow(), null, 0L);
        CreditNote foreign = partialReturn(CreditBalance.of(other, List.of()), "DEV-X", credit(1, "1"));

        assertThatThrownBy(() -> CreditBalance.of(invoice, List.of(foreign)))
                .isInstanceOf(InvalidCreditNoteException.class)
                .hasMessageContaining("no pertenece a la factura");
    }
}
