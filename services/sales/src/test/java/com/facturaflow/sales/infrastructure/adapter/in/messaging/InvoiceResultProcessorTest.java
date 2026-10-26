package com.facturaflow.sales.infrastructure.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.facturaflow.sales.application.exception.SaleConcurrentModificationException;
import com.facturaflow.sales.application.port.in.RecordInvoiceResultCommand;
import com.facturaflow.sales.application.port.in.RecordInvoiceResultOutcome;
import com.facturaflow.sales.domain.exception.InvalidInvoicingOutcomeException;
import com.facturaflow.sales.domain.model.DocumentType;
import com.facturaflow.sales.domain.model.SaleStatus;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Traducción y clasificación de los resultados de facturación (ADR 0015), sin broker ni Spring. */
class InvoiceResultProcessorTest {

    private final List<RecordInvoiceResultCommand> recorded = new ArrayList<>();
    private Function<RecordInvoiceResultCommand, RecordInvoiceResultOutcome> behaviour =
            command -> new RecordInvoiceResultOutcome.Applied(SaleStatus.INVOICED);
    private final InvoiceResultProcessor processor = new InvoiceResultProcessor(new InvoiceResultTranslator(),
            command -> {
                recorded.add(command);
                return behaviour.apply(command);
            });

    private InvoiceResultOutcome process(String event) {
        return processor.process(event.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("cada evento se traduce a su comando, con el documento del cliente y el instante del hecho")
    void translatesEachEvent() {
        UUID invoiceId = UUID.randomUUID();
        UUID saleId = UUID.randomUUID();
        process(InvoiceResultEvents.issued("VENTA-1", invoiceId, "SETP1"));
        process(InvoiceResultEvents.rejected("VENTA-2", invoiceId, "motivo"));
        process(InvoiceResultEvents.requestRejected("VENTA-3", saleId, "otro motivo"));

        assertThat(recorded.get(0)).isInstanceOfSatisfying(RecordInvoiceResultCommand.InvoiceIssued.class, issued -> {
            assertThat(issued.documentType()).isEqualTo(DocumentType.NIT);
            assertThat(issued.number()).isEqualTo("SETP1");
            assertThat(issued.cufe()).isEqualTo(InvoiceResultEvents.CUFE);
            assertThat(issued.occurredAt()).hasToString("2026-10-26T18:00:00Z");
        });
        assertThat(recorded.get(1)).isInstanceOf(RecordInvoiceResultCommand.InvoiceRejected.class);
        assertThat(recorded.get(2)).isEqualTo(new RecordInvoiceResultCommand.InvoiceRequestRejected("VENTA-3", saleId,
                "otro motivo", recorded.get(2).occurredAt()));
    }

    @Test
    @DisplayName("aplicado, duplicado e ignorado son resultados completos (ack)")
    void completedOutcomes() {
        assertThat(process(InvoiceResultEvents.issued("VENTA-1", UUID.randomUUID(), "S")))
                .isInstanceOfSatisfying(InvoiceResultOutcome.Completed.class,
                        done -> assertThat(done.result()).isEqualTo(ConsumerState.Result.APPLIED));
        behaviour = command -> new RecordInvoiceResultOutcome.Ignored("no es de ninguna venta");
        assertThat(process(InvoiceResultEvents.issued("REST-1", UUID.randomUUID(), "S")))
                .isInstanceOfSatisfying(InvoiceResultOutcome.Completed.class,
                        done -> assertThat(done.result()).isEqualTo(ConsumerState.Result.IGNORED));
    }

    @Test
    @DisplayName("un conflicto de versión o una falla inesperada se reintentan; un resultado incompleto es inválido")
    void technicalAndInvalid() {
        behaviour = command -> {
            throw new SaleConcurrentModificationException(UUID.randomUUID(), null);
        };
        assertThat(process(InvoiceResultEvents.issued("VENTA-1", UUID.randomUUID(), "S")))
                .isInstanceOf(InvoiceResultOutcome.RetryLater.class);

        behaviour = command -> {
            throw InvalidInvoicingOutcomeException.missing("el CUFE de la factura");
        };
        assertThat(process(InvoiceResultEvents.issued("VENTA-1", UUID.randomUUID(), "S")))
                .isInstanceOf(InvoiceResultOutcome.Invalid.class);
    }

    @Test
    @DisplayName("JSON ilegible, otra versión u otro tipo de evento son inválidos y no llegan al caso de uso")
    void unreadableMessages() {
        assertThat(process("no es json")).isInstanceOf(InvoiceResultOutcome.Invalid.class);
        assertThat(process(InvoiceResultEvents.issued("V-1", UUID.randomUUID(), "S").replace("\"eventVersion\": 1",
                "\"eventVersion\": 2"))).isInstanceOf(InvoiceResultOutcome.Invalid.class);
        assertThat(process(InvoiceResultEvents.issued("V-1", UUID.randomUUID(), "S").replace("InvoiceIssued",
                "InvoiceCancelled"))).isInstanceOf(InvoiceResultOutcome.Invalid.class);
        assertThat(recorded).isEmpty();
    }
}
