package com.facturaflow.invoicing.infrastructure.adapter.in.messaging;

import com.facturaflow.invoicing.application.exception.ElectronicInvoiceProviderUnavailableException;
import com.facturaflow.invoicing.application.exception.InvoiceAlreadyRejectedException;
import com.facturaflow.invoicing.application.exception.InvoiceConcurrentModificationException;
import com.facturaflow.invoicing.application.exception.ReferenceCodeConflictException;
import com.facturaflow.invoicing.application.port.in.CreateInvoiceCommand;
import com.facturaflow.invoicing.application.port.in.CreateInvoiceResult;
import com.facturaflow.invoicing.application.port.in.CreateInvoiceUseCase;
import com.facturaflow.invoicing.application.port.in.IssueInvoiceCommand;
import com.facturaflow.invoicing.application.port.in.IssueInvoiceUseCase;
import com.facturaflow.invoicing.application.port.in.RejectInvoiceRequestCommand;
import com.facturaflow.invoicing.application.port.in.RejectInvoiceRequestUseCase;
import com.facturaflow.invoicing.domain.exception.DomainException;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.domain.model.InvoiceStatus;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Procesa un {@code SaleConfirmed} con los casos de uso EXISTENTES (ADR 0014), sin nada de RabbitMQ (se prueba
 * sin broker): crear la factura (idempotente por {@code referenceCode}) → comparar los totales informativos →
 * emitirla. Clasifica el resultado:
 * <table>
 *   <tr><td>emitida o rechazada por la DIAN, nueva o ya existente</td><td>{@link SaleConfirmedOutcome.Completed}</td></tr>
 *   <tr><td>falla técnica del proveedor (caído, circuito abierto, limitador), modificación concurrente o cualquier
 *       falla inesperada (p. ej. la base)</td><td>{@link SaleConfirmedOutcome.RetryLater}</td></tr>
 *   <tr><td>JSON ilegible, fuera del contrato, regla del dominio (422) o {@code referenceCode} ya usado con otro
 *       contenido</td><td>{@link SaleConfirmedOutcome.Invalid}</td></tr>
 * </table>
 * Un mensaje repetido (mismo evento u otro con el mismo contenido) devuelve la factura existente y NO vuelve a
 * llamar al proveedor: solo se emite si sigue PENDING (p. ej. tras una falla técnica).
 *
 * <p>Un mensaje inválido es un resultado DEFINITIVO: antes de mandarlo a la DLQ se registra
 * {@code InvoiceRequestRejected} (ADR 0015, D2) para que sales marque la venta INVOICE_FAILED. Si el mensaje ni
 * siquiera deja leer su {@code referenceCode}, no hay a quién avisar. Si registrar el rechazo falla (p. ej. la base),
 * se trata como falla técnica: se reintenta y no se pierde. Una falla técnica NUNCA se registra como rechazo: se
 * puede reprocesar.
 */
final class SaleConfirmedProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(SaleConfirmedProcessor.class);
    /** Los motivos viajan en cabeceras de la DLQ y en el health: cortos. */
    static final int MAX_REASON_LENGTH = 300;

    private final SaleConfirmedTranslator translator;
    private final SaleTotalsCheck totals;
    private final CreateInvoiceUseCase createInvoice;
    private final IssueInvoiceUseCase issueInvoice;
    private final RejectInvoiceRequestUseCase rejectRequest;

    SaleConfirmedProcessor(SaleConfirmedTranslator translator, SaleTotalsCheck totals,
                           CreateInvoiceUseCase createInvoice, IssueInvoiceUseCase issueInvoice,
                           RejectInvoiceRequestUseCase rejectRequest) {
        this.translator = translator;
        this.totals = totals;
        this.createInvoice = createInvoice;
        this.issueInvoice = issueInvoice;
        this.rejectRequest = rejectRequest;
    }

    SaleConfirmedOutcome process(byte[] body) {
        SaleConfirmedOutcome outcome = classify(body);
        return outcome instanceof SaleConfirmedOutcome.Invalid invalid ? notifyRejection(body, invalid) : outcome;
    }

    /** Registra el rechazo definitivo de la petición para quien la pidió (si se puede leer a quién). */
    private SaleConfirmedOutcome notifyRejection(byte[] body, SaleConfirmedOutcome.Invalid invalid) {
        RequestIds ids = RequestIds.read(body);
        if (ids.referenceCode() == null) {
            LOG.warn("Mensaje inválido sin referenceCode legible: no hay venta a la que avisar; solo va a la DLQ.");
            return invalid;
        }
        try {
            rejectRequest.reject(new RejectInvoiceRequestCommand(ids.referenceCode(), ids.saleId(), ids.eventId(),
                    shorten(invalid.reason())));
            return invalid;
        } catch (RuntimeException e) {
            LOG.error("No se pudo registrar el rechazo de la petición de la venta {}", ids.referenceCode(), e);
            return new SaleConfirmedOutcome.RetryLater(ids.referenceCode(), "No se pudo registrar el rechazo de la "
                    + "petición: " + e.getClass().getSimpleName());
        }
    }

    /**
     * Lo mínimo para avisar del rechazo, leído sin exigir el contrato completo (el mensaje es inválido): el
     * {@code referenceCode} (texto no vacío) y, si tienen forma de UUID, {@code saleId} y {@code eventId}.
     */
    record RequestIds(String referenceCode, UUID saleId, UUID eventId) {

        static RequestIds read(byte[] body) {
            JsonNode tree;
            try {
                tree = SaleConfirmedJson.MAPPER.readTree(body);
            } catch (JacksonException e) {
                return new RequestIds(null, null, null);
            }
            JsonNode payload = tree.path("payload");
            String reference = payload.path("referenceCode").isString() ? payload.path("referenceCode").asString()
                    : null;
            return new RequestIds(reference == null || reference.isBlank() ? null : reference.strip(),
                    uuid(payload.path("saleId")), uuid(tree.path("eventId")));
        }

        private static UUID uuid(JsonNode node) {
            try {
                return node.isString() ? UUID.fromString(node.asString()) : null;
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    private SaleConfirmedOutcome classify(byte[] body) {
        SaleConfirmedMessage message;
        CreateInvoiceCommand command;
        try {
            message = SaleConfirmedJson.MAPPER.readValue(body, SaleConfirmedMessage.class);
            command = translator.toCommand(message);
        } catch (JacksonException e) {
            return new SaleConfirmedOutcome.Invalid(null, "JSON ilegible o con tipos inválidos: "
                    + e.getOriginalMessage());
        } catch (InvalidSaleConfirmedMessageException e) {
            return new SaleConfirmedOutcome.Invalid(null, e.getMessage());
        }

        String referenceCode = command.referenceCode();
        try {
            CreateInvoiceResult result = createInvoice.create(command);
            Invoice invoice = result.invoice();
            if (result.created()) {
                totals.matches(message.payload().totals(), invoice);
            }
            if (invoice.status() == InvoiceStatus.PENDING) {
                // Nueva, o creada en un intento anterior que falló al emitir: se emite (fuera de transacción).
                invoice = issueInvoice.issue(new IssueInvoiceCommand(invoice.id().value()));
            }
            return new SaleConfirmedOutcome.Completed(invoice.id().value(), referenceCode, invoice.status(),
                    !result.created());
        } catch (InvoiceAlreadyRejectedException e) {
            // Otra entrega la rechazó entre nuestra lectura y nuestra emisión: el resultado de negocio ya existe.
            return new SaleConfirmedOutcome.Completed(e.invoiceId(), referenceCode, InvoiceStatus.REJECTED, true);
        } catch (DomainException | ReferenceCodeConflictException e) {
            return new SaleConfirmedOutcome.Invalid(referenceCode, e.getMessage());
        } catch (ElectronicInvoiceProviderUnavailableException e) {
            return new SaleConfirmedOutcome.RetryLater(referenceCode, "Proveedor de facturación no disponible ("
                    + e.reason() + ")");
        } catch (InvoiceConcurrentModificationException e) {
            return new SaleConfirmedOutcome.RetryLater(referenceCode, "Modificación concurrente de la factura");
        } catch (RuntimeException e) {
            // Inesperado (la base caída, un bug): se reintenta; si persiste, termina en la DLQ.
            LOG.error("Falla inesperada procesando la venta {}", referenceCode, e);
            return new SaleConfirmedOutcome.RetryLater(referenceCode, "Falla inesperada: " + e.getClass().getSimpleName());
        }
    }

    static String shorten(String reason) {
        return reason == null || reason.length() <= MAX_REASON_LENGTH ? reason : reason.substring(0, MAX_REASON_LENGTH);
    }
}
