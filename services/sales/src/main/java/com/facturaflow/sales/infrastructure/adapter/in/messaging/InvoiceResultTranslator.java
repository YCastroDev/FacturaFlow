package com.facturaflow.sales.infrastructure.adapter.in.messaging;

import com.facturaflow.sales.application.port.in.RecordInvoiceResultCommand;
import com.facturaflow.sales.domain.model.DocumentType;
import com.facturaflow.sales.domain.model.InvoicingOutcome;
import java.util.Objects;

/**
 * Traduce un evento de resultado v1 al {@link RecordInvoiceResultCommand} (ADR 0015). Valida solo lo que necesita
 * (tipo y versión, campos que se usan, enums conocidos); las reglas las aplica el dominio. El motivo de un fallo se
 * recorta al máximo que guarda la venta.
 */
final class InvoiceResultTranslator {

    static final int EVENT_VERSION = 1;

    RecordInvoiceResultCommand toCommand(InvoiceResultMessage message) {
        if (message == null) {
            throw new InvalidInvoiceResultMessageException("El mensaje está vacío");
        }
        if (!Objects.equals(EVENT_VERSION, message.eventVersion())) {
            throw new InvalidInvoiceResultMessageException("Versión no soportada: " + message.eventType() + " v"
                    + message.eventVersion());
        }
        InvoiceResultMessage.Payload payload = require(message.payload(), "payload");
        String referenceCode = require(payload.referenceCode(), "payload.referenceCode");
        return switch (require(message.eventType(), "eventType")) {
            case "InvoiceIssued" -> {
                InvoiceResultMessage.Customer customer = require(payload.customer(), "payload.customer");
                yield new RecordInvoiceResultCommand.InvoiceIssued(referenceCode, documentType(customer),
                        customer.documentNumber(), require(payload.invoiceId(), "payload.invoiceId"),
                        payload.number(), payload.cufe(), require(message.occurredAt(), "occurredAt"));
            }
            case "InvoiceRejected" -> {
                InvoiceResultMessage.Customer customer = require(payload.customer(), "payload.customer");
                yield new RecordInvoiceResultCommand.InvoiceRejected(referenceCode, documentType(customer),
                        customer.documentNumber(), require(payload.invoiceId(), "payload.invoiceId"),
                        shorten(payload.reason()), require(message.occurredAt(), "occurredAt"));
            }
            case "InvoiceRequestRejected" -> new RecordInvoiceResultCommand.InvoiceRequestRejected(referenceCode,
                    payload.saleId(), shorten(payload.reason()), require(message.occurredAt(), "occurredAt"));
            default -> throw new InvalidInvoiceResultMessageException("Evento no soportado: " + message.eventType());
        };
    }

    private static DocumentType documentType(InvoiceResultMessage.Customer customer) {
        String name = require(customer.documentType(), "payload.customer.documentType");
        try {
            return DocumentType.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new InvalidInvoiceResultMessageException("Tipo de documento desconocido: " + name);
        }
    }

    private static String shorten(String reason) {
        int max = InvoicingOutcome.Failed.MAX_REASON_LENGTH;
        return reason == null || reason.length() <= max ? reason : reason.substring(0, max);
    }

    private static <T> T require(T value, String field) {
        if (value == null) {
            throw new InvalidInvoiceResultMessageException("Falta " + field);
        }
        return value;
    }
}
