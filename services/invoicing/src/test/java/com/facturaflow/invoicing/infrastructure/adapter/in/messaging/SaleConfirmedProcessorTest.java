package com.facturaflow.invoicing.infrastructure.adapter.in.messaging;

import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.electronicDocument;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.pendingInvoice;
import static com.facturaflow.invoicing.infrastructure.adapter.in.messaging.SaleConfirmedEvents.sale;
import static com.facturaflow.invoicing.infrastructure.adapter.in.messaging.SaleConfirmedEvents.withSalesTotal;
import static org.assertj.core.api.Assertions.assertThat;

import com.facturaflow.invoicing.application.exception.ElectronicInvoiceProviderUnavailableException;
import com.facturaflow.invoicing.application.exception.InvoiceAlreadyRejectedException;
import com.facturaflow.invoicing.application.exception.InvoiceConcurrentModificationException;
import com.facturaflow.invoicing.application.exception.ReferenceCodeConflictException;
import com.facturaflow.invoicing.application.port.in.CreateInvoiceCommand;
import com.facturaflow.invoicing.application.port.in.CreateInvoiceResult;
import com.facturaflow.invoicing.application.port.in.CreateInvoiceUseCase;
import com.facturaflow.invoicing.application.port.in.IssueInvoiceUseCase;
import com.facturaflow.invoicing.domain.exception.InvalidInvoiceException;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.domain.model.InvoiceStatus;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * La clasificación del resultado (ADR 0014) sin broker ni Spring: los casos de uso son dobles que devuelven
 * facturas reales del dominio o lanzan las excepciones de su contrato.
 */
@ExtendWith(OutputCaptureExtension.class)
class SaleConfirmedProcessorTest {

    private final List<CreateInvoiceCommand> created = new ArrayList<>();
    private final List<Invoice> issued = new ArrayList<>();
    private Function<CreateInvoiceCommand, CreateInvoiceResult> createBehaviour =
            command -> CreateInvoiceResult.created(pendingInvoice());
    private Function<Invoice, Invoice> issueBehaviour = invoice -> {
        invoice.markAsIssued(electronicDocument(invoice.total().amount().toPlainString()));
        return invoice;
    };

    private final CreateInvoiceUseCase createInvoice = command -> {
        created.add(command);
        return createBehaviour.apply(command);
    };
    private final IssueInvoiceUseCase issueInvoice = command -> {
        Invoice invoice = pendingInvoice();
        issued.add(invoice);
        return issueBehaviour.apply(invoice);
    };
    private final SaleConfirmedProcessor processor = new SaleConfirmedProcessor(new SaleConfirmedTranslator(),
            new SaleTotalsCheck(), createInvoice, issueInvoice);

    private SaleConfirmedOutcome process(String event) {
        return processor.process(event.getBytes(StandardCharsets.UTF_8));
    }

    private static Invoice issuedInvoice() {
        Invoice invoice = pendingInvoice();
        invoice.markAsIssued(electronicDocument(invoice.total().amount().toPlainString()));
        return invoice;
    }

    @Test
    @DisplayName("una venta nueva crea la factura y la emite: resultado de negocio, ack")
    void newSaleIsCreatedAndIssued() {
        SaleConfirmedOutcome outcome = process(sale("VENTA-1"));

        assertThat(outcome).isInstanceOfSatisfying(SaleConfirmedOutcome.Completed.class, done -> {
            assertThat(done.status()).isEqualTo(InvoiceStatus.ISSUED);
            assertThat(done.duplicate()).isFalse();
            assertThat(done.referenceCode()).isEqualTo("VENTA-1");
        });
        assertThat(created).extracting(CreateInvoiceCommand::referenceCode).containsExactly("VENTA-1");
        assertThat(issued).hasSize(1);
    }

    @Test
    @DisplayName("un rechazo de la DIAN es un resultado de negocio: ack, sin reintentos")
    void rejectionIsCompleted() {
        issueBehaviour = invoice -> {
            invoice.markAsRejected("La DIAN no validó el documento");
            return invoice;
        };

        assertThat(process(sale("VENTA-1"))).isInstanceOfSatisfying(SaleConfirmedOutcome.Completed.class,
                done -> assertThat(done.status()).isEqualTo(InvoiceStatus.REJECTED));
    }

    @Test
    @DisplayName("un mensaje repetido de una factura ya emitida no vuelve a llamar a la emisión")
    void duplicateOfIssuedInvoiceDoesNotIssueAgain() {
        createBehaviour = command -> CreateInvoiceResult.replayed(issuedInvoice());

        SaleConfirmedOutcome outcome = process(sale("VENTA-1"));

        assertThat(outcome).isInstanceOfSatisfying(SaleConfirmedOutcome.Completed.class, done -> {
            assertThat(done.duplicate()).isTrue();
            assertThat(done.status()).isEqualTo(InvoiceStatus.ISSUED);
        });
        assertThat(issued).isEmpty();
    }

    @Test
    @DisplayName("si la factura quedó PENDING en un intento anterior (falla técnica), el reintento la emite")
    void pendingFromPreviousAttemptIsIssued() {
        createBehaviour = command -> CreateInvoiceResult.replayed(pendingInvoice());

        assertThat(process(sale("VENTA-1"))).isInstanceOf(SaleConfirmedOutcome.Completed.class);
        assertThat(issued).hasSize(1);
    }

    @Test
    @DisplayName("si otra entrega ya la rechazó, el resultado de negocio existe: ack")
    void alreadyRejectedIsCompleted() {
        issueBehaviour = invoice -> {
            throw new InvoiceAlreadyRejectedException(invoice.id().value());
        };

        assertThat(process(sale("VENTA-1"))).isInstanceOfSatisfying(SaleConfirmedOutcome.Completed.class, done -> {
            assertThat(done.status()).isEqualTo(InvoiceStatus.REJECTED);
            assertThat(done.duplicate()).isTrue();
        });
    }

    @Test
    @DisplayName("una falla técnica del proveedor (caído, circuito abierto, limitador) se reintenta más tarde")
    void providerUnavailableIsRetried() {
        issueBehaviour = invoice -> {
            throw ElectronicInvoiceProviderUnavailableException.of(ElectronicInvoiceProviderUnavailableException.Document.INVOICE,
                    invoice.id().value(), "circuito abierto", null,
                    ElectronicInvoiceProviderUnavailableException.Reason.CIRCUIT_OPEN, null);
        };

        assertThat(process(sale("VENTA-1"))).isInstanceOfSatisfying(SaleConfirmedOutcome.RetryLater.class,
                retry -> assertThat(retry.reason()).contains("CIRCUIT_OPEN"));
    }

    @Test
    @DisplayName("una modificación concurrente o una falla inesperada (p. ej. la base) también se reintenta")
    void concurrentModificationAndUnexpectedAreRetried() {
        issueBehaviour = invoice -> {
            throw new InvoiceConcurrentModificationException(invoice.id().value(), null);
        };
        assertThat(process(sale("VENTA-1"))).isInstanceOf(SaleConfirmedOutcome.RetryLater.class);

        createBehaviour = command -> {
            throw new IllegalStateException("conexión rechazada");
        };
        assertThat(process(sale("VENTA-2"))).isInstanceOfSatisfying(SaleConfirmedOutcome.RetryLater.class,
                retry -> assertThat(retry.reason()).isEqualTo("Falla inesperada: IllegalStateException"));
    }

    @Test
    @DisplayName("una regla del dominio (422) o un referenceCode ya usado con otro contenido es un mensaje inválido")
    void domainRulesAndConflictsAreInvalid() {
        createBehaviour = command -> {
            throw InvalidInvoiceException.noLines();
        };
        assertThat(process(sale("VENTA-1"))).isInstanceOf(SaleConfirmedOutcome.Invalid.class);

        createBehaviour = command -> {
            throw new ReferenceCodeConflictException(command.referenceCode());
        };
        assertThat(process(sale("VENTA-2"))).isInstanceOfSatisfying(SaleConfirmedOutcome.Invalid.class,
                invalid -> assertThat(invalid.referenceCode()).isEqualTo("VENTA-2"));
        assertThat(issued).isEmpty();
    }

    @Test
    @DisplayName("un JSON ilegible o fuera del contrato es inválido y ni siquiera llega a los casos de uso")
    void unreadableMessagesAreInvalid() {
        assertThat(process("no es json")).isInstanceOf(SaleConfirmedOutcome.Invalid.class);
        assertThat(process("{\"eventType\":\"SaleConfirmed\",\"eventVersion\":2}"))
                .isInstanceOfSatisfying(SaleConfirmedOutcome.Invalid.class,
                        invalid -> assertThat(invalid.reason()).contains("v2"));
        assertThat(created).isEmpty();
    }

    @Test
    @DisplayName("totales distintos: WARN con ambos valores y la factura sigue con los de invoicing")
    void differentTotalsAreLogged(CapturedOutput output) {
        // La factura de prueba vale 119.000 (100.000 + IVA 19 %); el evento dice 34.965 y luego 34.966.
        process(withSalesTotal(sale("VENTA-1"), "34966.00"));

        assertThat(output).contains("Totales distintos entre sales e invoicing", "total=34966.00", "total=119000.00");
    }

    @Test
    @DisplayName("los totales solo se comparan al crear la factura, no en cada duplicado")
    void totalsAreComparedOnlyOnCreation(CapturedOutput output) {
        createBehaviour = command -> CreateInvoiceResult.replayed(issuedInvoice());

        process(withSalesTotal(sale("VENTA-1"), "1.00"));

        assertThat(output).doesNotContain("Totales distintos");
    }
}
