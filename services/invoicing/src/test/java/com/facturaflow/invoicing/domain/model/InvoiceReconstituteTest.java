package com.facturaflow.invoicing.domain.model;

import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.INVOICE_ID;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.ISSUE_DATE;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.customer;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.electronicDocument;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.line;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.pendingInvoice;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.invoicing.domain.exception.InvalidInvoiceException;
import com.facturaflow.invoicing.domain.exception.InvalidInvoiceStateTransitionException;
import com.facturaflow.invoicing.domain.exception.InvalidPaymentTermsException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class InvoiceReconstituteTest {

    private static final List<InvoiceLine> LINES = List.of(line("1", "100000.00", "0", "19"));

    private static Invoice reconstitute(InvoiceStatus status, ElectronicDocument document, String reason,
                                        long version) {
        return Invoice.reconstitute(INVOICE_ID, new ReferenceCode("SALE-0001"), customer(), LINES,
                PaymentMethod.CASH, PaymentTerms.cash(), ISSUE_DATE, null, status, document, reason, version);
    }

    @Test
    @DisplayName("una factura nueva no tiene versión: la asigna la persistencia al guardarla")
    void newInvoiceHasNoVersion() {
        assertThat(pendingInvoice().version()).isEmpty();
    }

    @Test
    @DisplayName("una factura pendiente se reconstruye con su versión y puede seguir su ciclo de vida")
    void reconstitutesPendingInvoice() {
        Invoice invoice = reconstitute(InvoiceStatus.PENDING, null, null, 3);

        assertThat(invoice.status()).isEqualTo(InvoiceStatus.PENDING);
        assertThat(invoice.version()).contains(3L);

        invoice.markAsIssued(electronicDocument("119000.00"));
        assertThat(invoice.status()).isEqualTo(InvoiceStatus.ISSUED);
    }

    @Test
    @DisplayName("una factura emitida se reconstruye con su documento electrónico y ya no cambia de estado")
    void reconstitutesIssuedInvoice() {
        ElectronicDocument document = electronicDocument("119000.00");
        Invoice invoice = reconstitute(InvoiceStatus.ISSUED, document, null, 1);

        assertThat(invoice.electronicDocument()).contains(document);
        assertThatThrownBy(() -> invoice.markAsRejected("tarde"))
                .isInstanceOf(InvalidInvoiceStateTransitionException.class);
    }

    @Test
    @DisplayName("una factura rechazada se reconstruye con su motivo")
    void reconstitutesRejectedInvoice() {
        Invoice invoice = reconstitute(InvoiceStatus.REJECTED, null, " NIT inválido ", 1);

        assertThat(invoice.rejectionReason()).hasValue("NIT inválido");
    }

    @ParameterizedTest(name = "{0} con documento={1} y motivo=''{2}''")
    @CsvSource({
            "PENDING,  true,  ,              no puede tener documento electrónico",
            "PENDING,  false, motivo,        no puede tener motivo de rechazo",
            "ISSUED,   false, ,              requiere documento electrónico",
            "ISSUED,   true,  motivo,        no puede tener motivo de rechazo",
            "REJECTED, false, ,              requiere motivo de rechazo",
            "REJECTED, false, '   ',         requiere motivo de rechazo",
            "REJECTED, true,  motivo,        no puede tener documento electrónico"
    })
    @DisplayName("un estado incoherente se rechaza al reconstruir")
    void rejectsInconsistentState(InvoiceStatus status, boolean withDocument, String reason, String expected) {
        ElectronicDocument document = withDocument ? electronicDocument("119000.00") : null;

        assertThatThrownBy(() -> reconstitute(status, document, reason, 1))
                .isInstanceOf(InvalidInvoiceException.class)
                .hasMessageContaining("Estado incoherente")
                .hasMessageContaining(expected);
    }

    @Test
    @DisplayName("una versión negativa se rechaza")
    void rejectsNegativeVersion() {
        assertThatThrownBy(() -> reconstitute(InvoiceStatus.PENDING, null, null, -1))
                .isInstanceOf(InvalidInvoiceException.class)
                .hasMessageContaining("versión");
    }

    @Test
    @DisplayName("al reconstruir se aplican las mismas reglas que al crear")
    void appliesCreationRules() {
        assertThatThrownBy(() -> Invoice.reconstitute(INVOICE_ID, new ReferenceCode("SALE-0001"), customer(),
                List.of(), PaymentMethod.CASH, PaymentTerms.cash(), ISSUE_DATE, null, InvoiceStatus.PENDING,
                null, null, 0))
                .isInstanceOf(InvalidInvoiceException.class)
                .hasMessageContaining("al menos una línea");
        assertThatThrownBy(() -> Invoice.reconstitute(INVOICE_ID, new ReferenceCode("SALE-0001"), customer(),
                LINES, PaymentMethod.CASH, PaymentTerms.credit(ISSUE_DATE), ISSUE_DATE, null, InvoiceStatus.PENDING,
                null, null, 0))
                .isInstanceOf(InvalidPaymentTermsException.class);
    }
}
