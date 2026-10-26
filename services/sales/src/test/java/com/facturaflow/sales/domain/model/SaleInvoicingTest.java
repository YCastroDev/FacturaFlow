package com.facturaflow.sales.domain.model;

import static com.facturaflow.sales.domain.model.SaleFixtures.CREATED_AT;
import static com.facturaflow.sales.domain.model.SaleFixtures.SALE_DATE;
import static com.facturaflow.sales.domain.model.SaleFixtures.SALE_ID;
import static com.facturaflow.sales.domain.model.SaleFixtures.customer;
import static com.facturaflow.sales.domain.model.SaleFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.sales.domain.exception.InvalidInvoicingOutcomeException;
import com.facturaflow.sales.domain.exception.InvalidSaleStateTransitionException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** El resultado de la facturación en la venta (ADR 0015): transiciones y coherencia con el estado. */
class SaleInvoicingTest {

    private static final Instant AT = Instant.parse("2026-10-23T15:00:05Z");
    private static final InvoiceReference INVOICE =
            new InvoiceReference(UUID.fromString("7f1c2a4e-0000-4000-8000-000000000001"), "SETP990000001", "ab".repeat(48));

    private static Sale saved(SaleStatus status, InvoicingOutcome invoicing) {
        return Sale.reconstitute(SALE_ID, new ReferenceCode("VENTA-0001"), SALE_DATE, CREATED_AT, customer(),
                List.of(line("1", "1000", "0", "19")), PaymentMethod.CASH, PaymentTerms.cash(), null, status, invoicing, 0);
    }

    private static Sale confirmed() {
        return saved(SaleStatus.CONFIRMED, null);
    }

    @ParameterizedTest(name = "{0} → {1}: {2}")
    @CsvSource({
            "CONFIRMED, INVOICED, true", "CONFIRMED, INVOICE_FAILED, true",
            "INVOICE_FAILED, INVOICED, true", "INVOICE_FAILED, INVOICE_FAILED, false",
            "INVOICED, INVOICE_FAILED, false", "INVOICED, INVOICED, false",
            "INVOICED, CONFIRMED, false", "INVOICE_FAILED, CONFIRMED, false"})
    @DisplayName("el éxito gana y el fallo no deshace: transiciones permitidas")
    void transitions(SaleStatus from, SaleStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @Test
    @DisplayName("una venta confirmada pasa a INVOICED con su factura, como una venta NUEVA (la original no cambia)")
    void confirmedBecomesInvoiced() {
        Sale sale = confirmed();

        Sale invoiced = sale.invoiced(INVOICE, AT);

        assertThat(invoiced.status()).isEqualTo(SaleStatus.INVOICED);
        assertThat(invoiced.invoicing()).contains(new InvoicingOutcome.Invoiced(INVOICE, AT));
        assertThat(invoiced.version()).as("la versión es la leída: el repositorio la sube").contains(0L);
        assertThat(sale.status()).isEqualTo(SaleStatus.CONFIRMED);
        assertThat(sale.invoicing()).isEmpty();
    }

    @Test
    @DisplayName("una venta confirmada pasa a INVOICE_FAILED con el motivo; luego una factura emitida la deja INVOICED")
    void failedThenInvoiced() {
        Sale failed = confirmed().invoiceFailed("  La DIAN no validó el documento ", null, AT);

        assertThat(failed.status()).isEqualTo(SaleStatus.INVOICE_FAILED);
        assertThat(failed.invoicing()).contains(new InvoicingOutcome.Failed("La DIAN no validó el documento", null, AT));
        assertThat(failed.invoiced(INVOICE, AT.plusSeconds(60)).status()).isEqualTo(SaleStatus.INVOICED);
    }

    @Test
    @DisplayName("una venta facturada no puede fallar ni volver a facturarse")
    void invoicedIsFinal() {
        Sale invoiced = confirmed().invoiced(INVOICE, AT);

        assertThatThrownBy(() -> invoiced.invoiceFailed("tarde", null, AT))
                .isInstanceOf(InvalidSaleStateTransitionException.class).hasMessageContaining("INVOICED");
        assertThatThrownBy(() -> invoiced.invoiced(INVOICE, AT)).isInstanceOf(InvalidSaleStateTransitionException.class);
    }

    @Test
    @DisplayName("el estado guardado debe ser coherente con el resultado de la facturación")
    void reconstituteRequiresCoherence() {
        InvoicingOutcome invoiced = new InvoicingOutcome.Invoiced(INVOICE, AT);
        InvoicingOutcome failed = new InvoicingOutcome.Failed("motivo", null, AT);

        assertThatThrownBy(() -> saved(SaleStatus.CONFIRMED, invoiced)).isInstanceOf(InvalidInvoicingOutcomeException.class);
        assertThatThrownBy(() -> saved(SaleStatus.INVOICED, null)).isInstanceOf(InvalidInvoicingOutcomeException.class);
        assertThatThrownBy(() -> saved(SaleStatus.INVOICED, failed)).isInstanceOf(InvalidInvoicingOutcomeException.class);
        assertThatThrownBy(() -> saved(SaleStatus.INVOICE_FAILED, invoiced))
                .isInstanceOf(InvalidInvoicingOutcomeException.class);
        assertThat(saved(SaleStatus.INVOICED, invoiced).invoicing()).contains(invoiced);
    }

    @Test
    @DisplayName("la factura exige id, número y CUFE; el fallo, un motivo de hasta 1000 caracteres y su instante")
    void outcomeValidation() {
        assertThatThrownBy(() -> new InvoiceReference(null, "N", "C")).isInstanceOf(InvalidInvoicingOutcomeException.class);
        assertThatThrownBy(() -> new InvoiceReference(UUID.randomUUID(), " ", "C"))
                .isInstanceOf(InvalidInvoicingOutcomeException.class);
        assertThatThrownBy(() -> new InvoicingOutcome.Failed(" ", null, AT)).isInstanceOf(InvalidInvoicingOutcomeException.class);
        assertThatThrownBy(() -> new InvoicingOutcome.Failed("x".repeat(1001), null, AT))
                .isInstanceOf(InvalidInvoicingOutcomeException.class);
        assertThatThrownBy(() -> new InvoicingOutcome.Failed("motivo", null, null))
                .isInstanceOf(InvalidInvoicingOutcomeException.class);
    }
}
