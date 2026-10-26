package com.facturaflow.sales.domain.model;

import static com.facturaflow.sales.domain.model.SaleFixtures.CREATED_AT;
import static com.facturaflow.sales.domain.model.SaleFixtures.SALE_DATE;
import static com.facturaflow.sales.domain.model.SaleFixtures.SALE_ID;
import static com.facturaflow.sales.domain.model.SaleFixtures.customer;
import static com.facturaflow.sales.domain.model.SaleFixtures.excludedLine;
import static com.facturaflow.sales.domain.model.SaleFixtures.line;
import static com.facturaflow.sales.domain.model.SaleFixtures.saleWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.sales.domain.exception.InvalidPaymentTermsException;
import com.facturaflow.sales.domain.exception.InvalidSaleException;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SaleTest {

    private static Sale sale(PaymentTerms terms, String observation) {
        return Sale.confirm(SALE_ID, new ReferenceCode("VENTA-0001"), SALE_DATE, CREATED_AT, customer(),
                List.of(line("1", "1000", "0", "19")), PaymentMethod.CASH, terms, observation);
    }

    @Nested
    @DisplayName("Confirmación")
    class Confirmation {

        @Test
        @DisplayName("una venta nueva nace CONFIRMED, sin versión y con la copia del cliente")
        void newSaleIsConfirmed() {
            Sale sale = sale(PaymentTerms.cash(), "  Entregar en portería ");

            assertThat(sale.status()).isEqualTo(SaleStatus.CONFIRMED);
            assertThat(sale.version()).isEmpty();
            assertThat(sale.customer().displayName()).isEqualTo("Cliente Ejemplo S.A.S.");
            assertThat(sale.observation()).contains("Entregar en portería");
        }

        @Test
        @DisplayName("a crédito, el vencimiento debe ser posterior a la fecha de la venta")
        void creditDueDateMustBeAfterSaleDate() {
            assertThat(sale(PaymentTerms.credit(SALE_DATE.plusDays(30)), null).paymentTerms())
                    .isEqualTo(PaymentTerms.credit(SALE_DATE.plusDays(30)));
            assertThatThrownBy(() -> sale(PaymentTerms.credit(SALE_DATE), null))
                    .isInstanceOf(InvalidPaymentTermsException.class)
                    .hasMessageContaining("posterior a la fecha de la venta");
        }

        @Test
        @DisplayName("una venta tiene de 1 a 100 líneas")
        void lineCount() {
            assertThatThrownBy(() -> saleWith(List.of())).isInstanceOf(InvalidSaleException.class)
                    .hasMessageContaining("entre 1 y 100");
            assertThat(saleWith(Collections.nCopies(100, line("1", "1", "0", "0"))).lines()).hasSize(100);
            assertThatThrownBy(() -> saleWith(Collections.nCopies(101, line("1", "1", "0", "0"))))
                    .isInstanceOf(InvalidSaleException.class);
        }

        @Test
        @DisplayName("la observación es opcional, se recorta y admite máximo 250 caracteres")
        void observation() {
            assertThat(sale(PaymentTerms.cash(), "   ").observation()).isEmpty();
            assertThatThrownBy(() -> sale(PaymentTerms.cash(), "x".repeat(251)))
                    .isInstanceOf(InvalidSaleException.class).hasMessageContaining("250");
        }

        @Test
        @DisplayName("reconstruir una venta a crédito ya vencida no falla: el vencimiento se validó al confirmarla")
        void reconstituteDoesNotRevalidateDueDate() {
            Sale sale = Sale.reconstitute(SALE_ID, new ReferenceCode("VENTA-0001"), SALE_DATE, CREATED_AT, customer(),
                    List.of(line("1", "1000", "0", "19")), PaymentMethod.CASH, PaymentTerms.credit(SALE_DATE.plusDays(1)),
                    null, SaleStatus.CONFIRMED, 3);

            assertThat(sale.version()).contains(3L);
        }

        @Test
        @DisplayName("CONFIRMED todavía no tiene transiciones: INVOICED e INVOICE_FAILED llegarán con los eventos")
        void noTransitionsYet() {
            assertThat(SaleStatus.CONFIRMED.canTransitionTo(SaleStatus.CONFIRMED)).isFalse();
        }
    }

    /** COPIA del bloque "Totales" de {@code InvoiceTest} de invoicing (ADR 0012): mismos casos y números. */
    @Nested
    @DisplayName("Totales")
    class Totals {

        @Test
        @DisplayName("los totales suman descuentos e impuestos de todas las líneas, incluida una excluida")
        void computesTotals() {
            // A: 2 x 50.000 - 10% = 90.000 + 19% (17.100)            = 107.100,00
            // B: 1 x 20.000 excluida                                  =  20.000,00
            // C: 3 x 1.999,99 = 5.999,97 - 5% (300,00) + 5% (285,00)  =   5.984,97
            Sale sale = saleWith(List.of(
                    line("2", "50000.00", "10", "19"),
                    excludedLine("1", "20000.00"),
                    line("3", "1999.99", "5", "5")));

            assertThat(sale.subtotal()).isEqualTo(Money.of("125999.97"));
            assertThat(sale.totalDiscount()).isEqualTo(Money.of("10300.00"));
            assertThat(sale.totalTax()).isEqualTo(Money.of("17385.00"));
            assertThat(sale.total()).isEqualTo(Money.of("133084.97"));
        }

        @Test
        @DisplayName("el impuesto total se desglosa por tipo y el desglose suma el total")
        void breaksDownTaxByType() {
            Sale sale = saleWith(List.of(
                    line("2", "50000.00", "10", LineTax.of(TaxType.IVA, "19"), LineTax.of(TaxType.INC, "8")),
                    line("1", "1000.00", "0", "5"),
                    excludedLine("1", "20000.00")));

            assertThat(sale.totalTax(TaxType.IVA)).isEqualTo(Money.of("17150.00"));   // 17.100 + 50
            assertThat(sale.totalTax(TaxType.INC)).isEqualTo(Money.of("7200.00"));
            assertThat(sale.totalTax()).isEqualTo(Money.of("24350.00"));
        }

        @Test
        @DisplayName("el total de la venta es exactamente la suma de los totales de sus líneas")
        void totalEqualsSumOfLineTotals() {
            Sale sale = saleWith(List.of(line("0.33", "0.33", "7", "19"), line("1.5", "333.33", "3", "5")));

            Money sumOfLines = sale.lines().stream().map(SaleLine::total).reduce(Money.ZERO, Money::plus);
            assertThat(sale.total()).isEqualTo(sumOfLines);
        }

        @Test
        @DisplayName("el impuesto se redondea por línea y la venta suma los valores ya redondeados")
        void taxIsRoundedPerLine() {
            // Cada línea: 0,10 x 5% = 0,005 -> 0,01. Por línea: 0,03. Sobre el total habría sido 0,015 -> 0,02.
            Sale sale = saleWith(List.of(
                    line("1", "0.10", "0", "5"), line("1", "0.10", "0", "5"), line("1", "0.10", "0", "5")));

            assertThat(sale.totalTax()).isEqualTo(Money.of("0.03"));
            assertThat(sale.total()).isEqualTo(Money.of("0.33"));
        }
    }

    @Nested
    @DisplayName("Eventos de dominio")
    class Events {

        @Test
        @DisplayName("confirmar una venta registra SaleConfirmed con su id, ocurrido al crearse")
        void confirmRegistersSaleConfirmed() {
            Sale sale = sale(PaymentTerms.cash(), null);

            assertThat(sale.pendingEvents()).containsExactly(new SaleEvent.SaleConfirmed(SALE_ID, CREATED_AT));
        }

        @Test
        @DisplayName("una venta reconstituida no trae eventos: ya se guardaron con la venta y no se repiten")
        void reconstitutedSaleHasNoEvents() {
            Sale sale = Sale.reconstitute(SALE_ID, new ReferenceCode("VENTA-0001"), SALE_DATE, CREATED_AT, customer(),
                    List.of(line("1", "1000", "0", "19")), PaymentMethod.CASH, PaymentTerms.cash(), null,
                    SaleStatus.CONFIRMED, 0);

            assertThat(sale.pendingEvents()).isEmpty();
        }

        @Test
        @DisplayName("los eventos pendientes no se pueden modificar desde fuera del agregado")
        void pendingEventsAreImmutable() {
            Sale sale = sale(PaymentTerms.cash(), null);

            assertThatThrownBy(() -> sale.pendingEvents().clear()).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("un evento exige el id de la venta y el instante en que ocurrió")
        void eventRequiresSaleIdAndInstant() {
            assertThatThrownBy(() -> new SaleEvent.SaleConfirmed(null, CREATED_AT))
                    .isInstanceOf(InvalidSaleException.class);
            assertThatThrownBy(() -> new SaleEvent.SaleConfirmed(SALE_ID, null))
                    .isInstanceOf(InvalidSaleException.class);
        }
    }
}
