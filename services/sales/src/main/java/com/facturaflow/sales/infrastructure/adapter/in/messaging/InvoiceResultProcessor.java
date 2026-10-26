package com.facturaflow.sales.infrastructure.adapter.in.messaging;

import com.facturaflow.sales.application.exception.SaleConcurrentModificationException;
import com.facturaflow.sales.application.port.in.RecordInvoiceResultCommand;
import com.facturaflow.sales.application.port.in.RecordInvoiceResultOutcome;
import com.facturaflow.sales.application.port.in.RecordInvoiceResultUseCase;
import com.facturaflow.sales.domain.exception.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;

/**
 * Procesa un resultado de facturación con {@link RecordInvoiceResultUseCase}, sin nada de RabbitMQ (se prueba sin
 * broker). Clasifica (ADR 0015):
 * <ul>
 *   <li>aplicado, duplicado o ignorado (venta desconocida, de otro cliente, fallo tardío) → {@code Completed}: ack;</li>
 *   <li>{@link SaleConcurrentModificationException} o cualquier falla inesperada (la base) → {@code RetryLater};</li>
 *   <li>JSON ilegible, fuera del contrato o datos que el dominio rechaza → {@code Invalid}: DLQ sin reintentos.</li>
 * </ul>
 */
final class InvoiceResultProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(InvoiceResultProcessor.class);
    static final int MAX_REASON_LENGTH = 300;

    private final InvoiceResultTranslator translator;
    private final RecordInvoiceResultUseCase recordResult;

    InvoiceResultProcessor(InvoiceResultTranslator translator, RecordInvoiceResultUseCase recordResult) {
        this.translator = translator;
        this.recordResult = recordResult;
    }

    InvoiceResultOutcome process(byte[] body) {
        RecordInvoiceResultCommand command;
        try {
            command = translator.toCommand(InvoiceResultJson.MAPPER.readValue(body, InvoiceResultMessage.class));
        } catch (JacksonException e) {
            return new InvoiceResultOutcome.Invalid(null, "JSON ilegible o con tipos inválidos: " + e.getOriginalMessage());
        } catch (InvalidInvoiceResultMessageException e) {
            return new InvoiceResultOutcome.Invalid(null, e.getMessage());
        }
        String referenceCode = command.referenceCode();
        try {
            return switch (recordResult.record(command)) {
                case RecordInvoiceResultOutcome.Applied applied -> new InvoiceResultOutcome.Completed(referenceCode,
                        ConsumerState.Result.APPLIED, applied.status().name());
                case RecordInvoiceResultOutcome.Duplicate duplicate -> new InvoiceResultOutcome.Completed(referenceCode,
                        ConsumerState.Result.DUPLICATE, duplicate.status().name());
                case RecordInvoiceResultOutcome.Ignored ignored -> new InvoiceResultOutcome.Completed(referenceCode,
                        ConsumerState.Result.IGNORED, ignored.reason());
            };
        } catch (DomainException e) {
            return new InvoiceResultOutcome.Invalid(referenceCode, e.getMessage());
        } catch (SaleConcurrentModificationException e) {
            return new InvoiceResultOutcome.RetryLater(referenceCode, "Modificación concurrente de la venta");
        } catch (RuntimeException e) {
            LOG.error("Falla inesperada aplicando el resultado de la venta {}", referenceCode, e);
            return new InvoiceResultOutcome.RetryLater(referenceCode, "Falla inesperada: " + e.getClass().getSimpleName());
        }
    }

    static String shorten(String reason) {
        return reason == null || reason.length() <= MAX_REASON_LENGTH ? reason : reason.substring(0, MAX_REASON_LENGTH);
    }
}
