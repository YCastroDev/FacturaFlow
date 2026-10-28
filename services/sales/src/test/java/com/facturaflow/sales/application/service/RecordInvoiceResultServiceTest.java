package com.facturaflow.sales.application.service;

import static com.facturaflow.sales.domain.model.SaleFixtures.CREATED_AT;
import static com.facturaflow.sales.domain.model.SaleFixtures.SALE_DATE;
import static com.facturaflow.sales.domain.model.SaleFixtures.SOLD_BY;
import static com.facturaflow.sales.domain.model.SaleFixtures.customer;
import static com.facturaflow.sales.domain.model.SaleFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.sales.application.port.in.RecordInvoiceResultCommand;
import com.facturaflow.sales.application.port.in.RecordInvoiceResultOutcome;
import com.facturaflow.sales.application.port.out.RequestHash;
import com.facturaflow.sales.domain.exception.InvalidInvoicingOutcomeException;
import com.facturaflow.sales.domain.model.DocumentType;
import com.facturaflow.sales.domain.model.InvoicingOutcome;
import com.facturaflow.sales.domain.model.PaymentMethod;
import com.facturaflow.sales.domain.model.PaymentTerms;
import com.facturaflow.sales.domain.model.ReferenceCode;
import com.facturaflow.sales.domain.model.Sale;
import com.facturaflow.sales.domain.model.SaleId;
import com.facturaflow.sales.domain.model.SaleStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Las reglas de D3 (ADR 0015) sin Spring ni broker: repositorio en memoria con bloqueo optimista. */
class RecordInvoiceResultServiceTest {

    private static final String REFERENCE = "VENTA-0001";
    private static final Instant T1 = Instant.parse("2026-10-23T15:00:05Z");
    private static final UUID INVOICE_ID = UUID.fromString("7f1c2a4e-0000-4000-8000-000000000001");
    private static final String CUFE = "ab".repeat(48);

    private final InMemorySaleRepository repository = new InMemorySaleRepository();
    private final RecordInvoiceResultService service = new RecordInvoiceResultService(repository);
    private Sale sale;

    @BeforeEach
    void confirmSale() {
        sale = repository.saveNew(Sale.confirm(SaleId.generate(), new ReferenceCode(REFERENCE), SALE_DATE, CREATED_AT,
                customer(), List.of(line("1", "1000", "0", "19")), PaymentMethod.CASH, PaymentTerms.cash(), null,
                SOLD_BY), new RequestHash("a".repeat(64)));
    }

    private static RecordInvoiceResultCommand issued(UUID invoiceId, String number) {
        return new RecordInvoiceResultCommand.InvoiceIssued(REFERENCE, DocumentType.NIT, "890903938", invoiceId, number,
                CUFE, T1);
    }

    private static RecordInvoiceResultCommand rejected() {
        return new RecordInvoiceResultCommand.InvoiceRejected(REFERENCE, DocumentType.NIT, "890903938", INVOICE_ID,
                "La DIAN no validó el documento", T1);
    }

    private RecordInvoiceResultCommand requestRejected(UUID saleId) {
        return new RecordInvoiceResultCommand.InvoiceRequestRejected(REFERENCE, saleId,
                "La fecha de vencimiento debe ser posterior a la fecha de emisión", T1);
    }

    private Sale current() {
        return repository.findByReferenceCode(new ReferenceCode(REFERENCE)).orElseThrow();
    }

    @Test
    @DisplayName("factura emitida: la venta pasa a INVOICED con el número y el CUFE")
    void issuedInvoicesTheSale() {
        RecordInvoiceResultOutcome outcome = service.record(issued(INVOICE_ID, "SETP990000001"));

        assertThat(outcome).isEqualTo(new RecordInvoiceResultOutcome.Applied(SaleStatus.INVOICED));
        assertThat(current().invoicing()).hasValueSatisfying(invoicing -> {
            InvoicingOutcome.Invoiced invoiced = (InvoicingOutcome.Invoiced) invoicing;
            assertThat(invoiced.invoice().number()).isEqualTo("SETP990000001");
            assertThat(invoiced.invoice().cufe()).isEqualTo(CUFE);
            assertThat(invoiced.at()).isEqualTo(T1);
        });
    }

    @Test
    @DisplayName("rechazo de la DIAN o de la petición: la venta pasa a INVOICE_FAILED con el motivo")
    void failuresFailTheSale() {
        assertThat(service.record(rejected())).isEqualTo(new RecordInvoiceResultOutcome.Applied(SaleStatus.INVOICE_FAILED));
        assertThat(current().invoicing()).contains(new InvoicingOutcome.Failed("La DIAN no validó el documento",
                INVOICE_ID, T1));
    }

    @Test
    @DisplayName("una petición rechazada aplica por referenceCode, y con saleId solo si es el de la venta")
    void requestRejectedChecksSaleId() {
        assertThat(service.record(requestRejected(UUID.randomUUID())))
                .isInstanceOf(RecordInvoiceResultOutcome.Ignored.class);
        assertThat(current().status()).isEqualTo(SaleStatus.CONFIRMED);

        assertThat(service.record(requestRejected(sale.id().value())))
                .isEqualTo(new RecordInvoiceResultOutcome.Applied(SaleStatus.INVOICE_FAILED));
    }

    @Test
    @DisplayName("un evento repetido no cambia nada ni falla")
    void duplicatesAreNoOps() {
        service.record(issued(INVOICE_ID, "SETP990000001"));
        int saves = repository.saves();

        assertThat(service.record(issued(INVOICE_ID, "SETP990000001")))
                .isEqualTo(new RecordInvoiceResultOutcome.Duplicate(SaleStatus.INVOICED));
        assertThat(repository.saves()).as("ni siquiera se guarda").isEqualTo(saves);
    }

    @Test
    @DisplayName("un fallo repetido conserva el primer motivo")
    void repeatedFailureKeepsFirstReason() {
        service.record(rejected());

        assertThat(service.record(requestRejected(null)))
                .isEqualTo(new RecordInvoiceResultOutcome.Duplicate(SaleStatus.INVOICE_FAILED));
        assertThat(((InvoicingOutcome.Failed) current().invoicing().orElseThrow()).reason())
                .isEqualTo("La DIAN no validó el documento");
    }

    @Test
    @DisplayName("orden inesperado: un fallo y luego un éxito deja la venta INVOICED (el éxito gana)")
    void successAfterFailureWins() {
        service.record(requestRejected(null));

        assertThat(service.record(issued(INVOICE_ID, "SETP990000001")))
                .isEqualTo(new RecordInvoiceResultOutcome.Applied(SaleStatus.INVOICED));
        assertThat(current().status()).isEqualTo(SaleStatus.INVOICED);
    }

    @Test
    @DisplayName("orden inesperado: un fallo después de un éxito se ignora (el fallo no deshace)")
    void failureAfterSuccessIsIgnored() {
        service.record(issued(INVOICE_ID, "SETP990000001"));

        assertThat(service.record(rejected())).isInstanceOf(RecordInvoiceResultOutcome.Ignored.class);
        assertThat(current().status()).isEqualTo(SaleStatus.INVOICED);
    }

    @Test
    @DisplayName("otra factura emitida para una venta ya facturada se ignora")
    void otherInvoiceForInvoicedSaleIsIgnored() {
        service.record(issued(INVOICE_ID, "SETP990000001"));

        assertThat(service.record(issued(UUID.randomUUID(), "SETP990000002")))
                .isInstanceOf(RecordInvoiceResultOutcome.Ignored.class);
        assertThat(((InvoicingOutcome.Invoiced) current().invoicing().orElseThrow()).invoice().number())
                .isEqualTo("SETP990000001");
    }

    @Test
    @DisplayName("una factura sin venta (p. ej. de la API REST) o de otro cliente se ignora sin cambiar nada")
    void foreignInvoicesAreIgnored() {
        RecordInvoiceResultCommand noSale = new RecordInvoiceResultCommand.InvoiceIssued("FACTURA-REST-1",
                DocumentType.NIT, "890903938", INVOICE_ID, "SETP1", CUFE, T1);
        RecordInvoiceResultCommand otherCustomer = new RecordInvoiceResultCommand.InvoiceIssued(REFERENCE,
                DocumentType.CITIZENSHIP_CARD, "1020304050", INVOICE_ID, "SETP1", CUFE, T1);
        RecordInvoiceResultCommand invalidCode = new RecordInvoiceResultCommand.InvoiceRequestRejected(
                "no es un código válido", null, "motivo", T1);

        assertThat(service.record(noSale)).isInstanceOf(RecordInvoiceResultOutcome.Ignored.class);
        assertThat(service.record(otherCustomer)).isInstanceOf(RecordInvoiceResultOutcome.Ignored.class);
        assertThat(service.record(invalidCode)).isInstanceOf(RecordInvoiceResultOutcome.Ignored.class);
        assertThat(current().status()).isEqualTo(SaleStatus.CONFIRMED);
    }

    @Test
    @DisplayName("un resultado incompleto (p. ej. sin CUFE) lo rechaza el dominio")
    void incompleteResultIsRejectedByTheDomain() {
        assertThatThrownBy(() -> service.record(issued(INVOICE_ID, null)))
                .isInstanceOf(InvalidInvoicingOutcomeException.class);
        assertThat(current().status()).isEqualTo(SaleStatus.CONFIRMED);
    }
}
