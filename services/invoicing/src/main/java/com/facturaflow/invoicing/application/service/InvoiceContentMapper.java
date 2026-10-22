package com.facturaflow.invoicing.application.service;

import com.facturaflow.invoicing.application.port.in.CreateInvoiceCommand;
import com.facturaflow.invoicing.domain.exception.InvalidInvoiceException;
import com.facturaflow.invoicing.domain.model.CustomerSnapshot;
import com.facturaflow.invoicing.domain.model.DiscountRate;
import com.facturaflow.invoicing.domain.model.FiscalProfile;
import com.facturaflow.invoicing.domain.model.IdentificationDocument;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.domain.model.InvoiceLine;
import com.facturaflow.invoicing.domain.model.LineTax;
import com.facturaflow.invoicing.domain.model.Money;
import com.facturaflow.invoicing.domain.model.Municipality;
import com.facturaflow.invoicing.domain.model.PaymentTerms;
import com.facturaflow.invoicing.domain.model.PhoneNumber;
import com.facturaflow.invoicing.domain.model.Quantity;
import com.facturaflow.invoicing.domain.model.TaxRate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Convierte el comando en value objects del dominio. Toda la validación de negocio ocurre
 * aquí, en los constructores del dominio: este mapper solo comprueba lo que necesita para
 * poder recorrer el comando (que existan las listas y objetos anidados).
 */
final class InvoiceContentMapper {

    private InvoiceContentMapper() {
    }

    static InvoiceContent toContent(CreateInvoiceCommand command) {
        if (command.customer() == null) {
            throw InvalidInvoiceException.missing("un cliente");
        }
        if (command.lines() == null || command.lines().isEmpty()) {
            throw InvalidInvoiceException.noLines();
        }
        if (command.lines().stream().anyMatch(Objects::isNull)) {
            throw InvalidInvoiceException.missing("líneas no nulas");
        }
        if (command.paymentMethod() == null) {
            throw InvalidInvoiceException.missing("un medio de pago");
        }
        if (command.paymentTerms() == null) {
            throw InvalidInvoiceException.missing("una forma de pago");
        }
        return new InvoiceContent(
                toCustomer(command.customer()),
                command.lines().stream().map(InvoiceContentMapper::toLine).toList(),
                command.paymentMethod(),
                toPaymentTerms(command.paymentTerms()),
                Invoice.normalizedObservation(command.observation()));
    }

    private static CustomerSnapshot toCustomer(CreateInvoiceCommand.Customer customer) {
        IdentificationDocument identification = new IdentificationDocument(customer.documentType(),
                customer.documentNumber(), customer.checkDigit());
        FiscalProfile fiscalProfile = new FiscalProfile(customer.taxScheme(),
                customer.fiscalResponsibilities() == null ? null : new HashSet<>(customer.fiscalResponsibilities()));
        return new CustomerSnapshot(
                identification,
                customer.personType(),
                customer.companyName(),
                customer.names(),
                fiscalProfile,
                customer.email(),
                isBlank(customer.municipalityCode()) ? null : new Municipality(customer.municipalityCode()),
                customer.address(),
                isBlank(customer.phone()) ? null : new PhoneNumber(customer.phone()));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static InvoiceLine toLine(CreateInvoiceCommand.Line line) {
        List<LineTax> taxes = line.taxes() == null ? null : line.taxes().stream()
                .map(tax -> tax == null ? null : new LineTax(tax.type(), new TaxRate(tax.rate())))
                .toList();
        return new InvoiceLine(
                line.code(),
                line.name(),
                new Quantity(line.quantity()),
                Money.of(line.unitPrice()),
                line.discountRate() == null ? DiscountRate.NONE : new DiscountRate(line.discountRate()),
                taxes,
                line.unit(),
                line.ivaExcluded());
    }

    private static PaymentTerms toPaymentTerms(CreateInvoiceCommand.Terms terms) {
        return switch (terms) {
            case CreateInvoiceCommand.CashTerms _ -> PaymentTerms.cash();
            case CreateInvoiceCommand.CreditTerms(var dueDate) -> PaymentTerms.credit(dueDate);
        };
    }
}
