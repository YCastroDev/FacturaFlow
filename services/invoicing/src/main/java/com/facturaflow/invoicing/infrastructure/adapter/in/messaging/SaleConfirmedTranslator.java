package com.facturaflow.invoicing.infrastructure.adapter.in.messaging;

import com.facturaflow.invoicing.application.port.in.CreateInvoiceCommand;
import com.facturaflow.invoicing.domain.model.DocumentType;
import com.facturaflow.invoicing.domain.model.FiscalResponsibility;
import com.facturaflow.invoicing.domain.model.PaymentMethod;
import com.facturaflow.invoicing.domain.model.PersonType;
import com.facturaflow.invoicing.domain.model.TaxScheme;
import com.facturaflow.invoicing.domain.model.TaxType;
import com.facturaflow.invoicing.domain.model.UnitOfMeasure;
import java.util.List;
import java.util.Objects;

/**
 * Traduce {@code SaleConfirmed} v1 al {@link CreateInvoiceCommand} que ya usa la API REST (ADR 0014): el mismo
 * núcleo, otro adaptador de entrada. El {@code referenceCode} de la venta es el de la factura, así la
 * idempotencia de invoicing (ADR 0006) convierte un mensaje repetido en "la factura ya existe".
 *
 * <p>Solo valida lo que necesita para armar el comando (tipo y versión del evento, objetos anidados presentes,
 * enums conocidos); las reglas de negocio las aplica el dominio dentro del caso de uso. Los enums se traducen por
 * nombre: son los mismos en los tres servicios y en el contrato.
 */
final class SaleConfirmedTranslator {

    static final String EVENT_TYPE = "SaleConfirmed";
    static final int EVENT_VERSION = 1;

    CreateInvoiceCommand toCommand(SaleConfirmedMessage message) {
        if (message == null) {
            throw new InvalidSaleConfirmedMessageException("El mensaje está vacío");
        }
        if (!EVENT_TYPE.equals(message.eventType()) || !Objects.equals(EVENT_VERSION, message.eventVersion())) {
            throw new InvalidSaleConfirmedMessageException("Evento no soportado: " + message.eventType() + " v"
                    + message.eventVersion() + " (se espera " + EVENT_TYPE + " v" + EVENT_VERSION + ")");
        }
        SaleConfirmedMessage.Payload payload = require(message.payload(), "payload");
        SaleConfirmedMessage.Customer customer = require(payload.customer(), "payload.customer");
        List<SaleConfirmedMessage.Line> lines = require(payload.lines(), "payload.lines");
        return new CreateInvoiceCommand(payload.referenceCode(), customer(customer),
                lines.stream().map(line -> line(require(line, "payload.lines[]"))).toList(),
                enumValue(PaymentMethod.class, payload.paymentMethod(), "paymentMethod"),
                terms(require(payload.paymentTerms(), "payload.paymentTerms")), payload.observation());
    }

    private static CreateInvoiceCommand.Customer customer(SaleConfirmedMessage.Customer customer) {
        List<FiscalResponsibility> responsibilities = require(customer.fiscalResponsibilities(),
                "payload.customer.fiscalResponsibilities").stream()
                .map(value -> enumValue(FiscalResponsibility.class, value, "fiscalResponsibilities"))
                .toList();
        return new CreateInvoiceCommand.Customer(
                enumValue(DocumentType.class, customer.documentType(), "documentType"), customer.documentNumber(),
                customer.checkDigit(), enumValue(PersonType.class, customer.personType(), "personType"),
                customer.companyName(), customer.names(), enumValue(TaxScheme.class, customer.taxScheme(), "taxScheme"),
                responsibilities, customer.email(), customer.municipalityCode(), customer.address(), customer.phone());
    }

    private static CreateInvoiceCommand.Line line(SaleConfirmedMessage.Line line) {
        List<CreateInvoiceCommand.Tax> taxes = require(line.taxes(), "payload.lines[].taxes").stream()
                .map(tax -> require(tax, "payload.lines[].taxes[]"))
                .map(tax -> new CreateInvoiceCommand.Tax(enumValue(TaxType.class, tax.type(), "taxes[].type"), tax.rate()))
                .toList();
        return new CreateInvoiceCommand.Line(line.code(), line.name(), line.quantity(), line.unitPrice(),
                line.discountRate(), enumValue(UnitOfMeasure.class, line.unit(), "unit"),
                Boolean.TRUE.equals(require(line.ivaExcluded(), "payload.lines[].ivaExcluded")), taxes);
    }

    private static CreateInvoiceCommand.Terms terms(SaleConfirmedMessage.Terms terms) {
        return switch (require(terms.type(), "payload.paymentTerms.type")) {
            case "CASH" -> new CreateInvoiceCommand.CashTerms();
            case "CREDIT" -> new CreateInvoiceCommand.CreditTerms(terms.dueDate());
            default -> throw new InvalidSaleConfirmedMessageException("Forma de pago desconocida: " + terms.type());
        };
    }

    /** {@code null} pasa como {@code null} (el dominio decide si era obligatorio); un nombre desconocido no. */
    private static <E extends Enum<E>> E enumValue(Class<E> type, String name, String field) {
        if (name == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            throw new InvalidSaleConfirmedMessageException("Valor desconocido en " + field + ": " + name);
        }
    }

    private static <T> T require(T value, String field) {
        if (value == null) {
            throw new InvalidSaleConfirmedMessageException("Falta " + field);
        }
        return value;
    }
}
