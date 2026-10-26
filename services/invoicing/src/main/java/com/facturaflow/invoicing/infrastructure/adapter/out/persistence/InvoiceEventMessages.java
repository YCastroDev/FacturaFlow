package com.facturaflow.invoicing.infrastructure.adapter.out.persistence;

import com.facturaflow.invoicing.application.port.out.OutboxMessage;
import com.facturaflow.invoicing.domain.model.ElectronicDocument;
import com.facturaflow.invoicing.domain.model.IdentificationDocument;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.domain.model.InvoiceEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Traduce los eventos de facturación a los mensajes que se publican en {@code invoicing.events} (ADR 0015). Es el
 * ÚNICO lugar que conoce los contratos {@code docs/events/invoice-issued.v1}, {@code invoice-rejected.v1} e
 * {@code invoice-request-rejected.v1}; {@code InvoiceEventsSchemaTest} valida contra ellos lo que produce. El
 * mensaje se serializa al guardar el evento: se publica (y republica) exactamente lo que había en ese momento.
 * Copia deliberada del patrón de sales ({@code SaleEventMessages}), sin código compartido (ADR 0001).
 *
 * <p>Nunca incluye {@code publicUrl} ni {@code qrUrl}: son enlaces-capacidad del proveedor (ADR 0008).
 */
@Component
class InvoiceEventMessages {

    static final String PRODUCER = "invoicing";
    static final String INVOICE = "Invoice";
    static final String INVOICE_REQUEST = "InvoiceRequest";
    static final String INVOICE_ISSUED = "InvoiceIssued";
    static final String INVOICE_REJECTED = "InvoiceRejected";
    static final String INVOICE_REQUEST_REJECTED = "InvoiceRequestRejected";
    static final int VERSION = 1;

    /** Orden de declaración (Jackson 3 ordena alfabéticamente por defecto) y decimales sin notación científica. */
    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    /** Un evento de la factura (emitida o rechazada), con los datos del agregado ya guardado. */
    OutboxMessage toMessage(InvoiceEvent event, Invoice invoice, Instant occurredAt) {
        UUID eventId = UUID.randomUUID();
        return switch (event) {
            case InvoiceEvent.InvoiceIssued issued -> {
                requireSame(issued.invoiceId().value(), invoice);
                ElectronicDocument document = invoice.electronicDocument().orElseThrow(() ->
                        new IllegalStateException("La factura " + invoice.id() + " emitida no tiene documento"));
                yield message(eventId, INVOICE_ISSUED, "invoice.issued.v1", occurredAt, new InvoiceIssuedV1(
                        invoice.id().value(), invoice.referenceCode().value(), customer(invoice), document.number(),
                        document.uniqueCode(), document.officialTotal().amount(), invoice.issueDate(),
                        document.validatedAt()));
            }
            case InvoiceEvent.InvoiceRejected rejected -> {
                requireSame(rejected.invoiceId().value(), invoice);
                yield message(eventId, INVOICE_REJECTED, "invoice.rejected.v1", occurredAt, new InvoiceRejectedV1(
                        invoice.id().value(), invoice.referenceCode().value(), customer(invoice),
                        invoice.rejectionReason().orElseThrow(), invoice.issueDate()));
            }
            case InvoiceEvent.InvoiceRequestRejected request -> throw new IllegalArgumentException(
                    "InvoiceRequestRejected no es de una factura: use toMessage(evento, instante)");
        };
    }

    /** Una petición de factura rechazada (no hay agregado). */
    OutboxMessage toMessage(InvoiceEvent.InvoiceRequestRejected event, Instant occurredAt) {
        return message(UUID.randomUUID(), INVOICE_REQUEST_REJECTED, "invoice-request.rejected.v1", occurredAt,
                new InvoiceRequestRejectedV1(event.referenceCode(), event.sourceId(), event.sourceEventId(),
                        event.reason()));
    }

    private static OutboxMessage message(UUID eventId, String type, String routingKey, Instant occurredAt,
                                         Object payload) {
        return new OutboxMessage(eventId, type, VERSION, routingKey,
                JSON.writeValueAsString(new Envelope(eventId, type, VERSION, occurredAt, PRODUCER, payload)), occurredAt);
    }

    private static Customer customer(Invoice invoice) {
        IdentificationDocument identification = invoice.customer().identification();
        return new Customer(identification.type().name(), identification.number());
    }

    private static void requireSame(UUID eventInvoiceId, Invoice invoice) {
        if (!eventInvoiceId.equals(invoice.id().value())) {
            throw new IllegalArgumentException("El evento de la factura " + eventInvoiceId + " no es de " + invoice.id());
        }
    }

    // ----- Contratos (docs/events/*.v1.schema.json). Un cambio aquí exige revisar el esquema. -----

    record Envelope(UUID eventId, String eventType, int eventVersion, Instant occurredAt, String producer,
                    Object payload) {
    }

    /** Identificación del cliente de la factura: con ella sales comprueba que la factura es de SU venta. */
    record Customer(String documentType, String documentNumber) {
    }

    record InvoiceIssuedV1(UUID invoiceId, String referenceCode, Customer customer, String number, String cufe,
                           BigDecimal officialTotal, LocalDate issueDate, Instant validatedAt) {
    }

    record InvoiceRejectedV1(UUID invoiceId, String referenceCode, Customer customer, String reason,
                             LocalDate issueDate) {
    }

    /** {@code saleId} y {@code sourceEventId}: de la petición que llegó (SaleConfirmed), si se pudieron leer. */
    record InvoiceRequestRejectedV1(String referenceCode, UUID saleId, UUID sourceEventId, String reason) {
    }
}
