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
import com.facturaflow.invoicing.domain.exception.DomainException;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.domain.model.InvoiceStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;

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
 */
final class SaleConfirmedProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(SaleConfirmedProcessor.class);
    /** Los motivos viajan en cabeceras de la DLQ y en el health: cortos. */
    static final int MAX_REASON_LENGTH = 300;

    private final SaleConfirmedTranslator translator;
    private final SaleTotalsCheck totals;
    private final CreateInvoiceUseCase createInvoice;
    private final IssueInvoiceUseCase issueInvoice;

    SaleConfirmedProcessor(SaleConfirmedTranslator translator, SaleTotalsCheck totals,
                           CreateInvoiceUseCase createInvoice, IssueInvoiceUseCase issueInvoice) {
        this.translator = translator;
        this.totals = totals;
        this.createInvoice = createInvoice;
        this.issueInvoice = issueInvoice;
    }

    SaleConfirmedOutcome process(byte[] body) {
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
