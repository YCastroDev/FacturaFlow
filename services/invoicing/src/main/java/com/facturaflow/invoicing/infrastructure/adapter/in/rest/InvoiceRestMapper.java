package com.facturaflow.invoicing.infrastructure.adapter.in.rest;

import com.facturaflow.invoicing.application.port.in.CreateInvoiceCommand;
import com.facturaflow.invoicing.domain.model.CustomerSnapshot;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.domain.model.InvoiceLine;
import com.facturaflow.invoicing.domain.model.Money;
import com.facturaflow.invoicing.domain.model.Municipality;
import com.facturaflow.invoicing.domain.model.PaymentTerms;
import com.facturaflow.invoicing.domain.model.PhoneNumber;
import com.facturaflow.invoicing.domain.model.TaxType;
import com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto.ApiDocumentType;
import com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto.ApiFiscalResponsibility;
import com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto.ApiInvoiceStatus;
import com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto.ApiPaymentMethod;
import com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto.ApiPaymentTermsType;
import com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto.ApiPersonType;
import com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto.ApiTaxScheme;
import com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto.ApiTaxType;
import com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto.ApiUnitOfMeasure;
import com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto.CreateInvoiceRequest;
import com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto.InvoiceResponse;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Traducción entre los DTOs HTTP y los comandos/resultados de los casos de uso. La respuesta
 * se construye LEYENDO el agregado (totales incluidos); el controlador nunca lo serializa.
 * Sin estado: métodos estáticos.
 */
final class InvoiceRestMapper {

    private InvoiceRestMapper() {
    }

    static CreateInvoiceCommand toCommand(CreateInvoiceRequest request) {
        CreateInvoiceRequest.Customer customer = request.customer();
        return new CreateInvoiceCommand(
                request.referenceCode(),
                new CreateInvoiceCommand.Customer(
                        customer.documentType().toDomain(),
                        customer.documentNumber(),
                        customer.checkDigit(),
                        customer.personType().toDomain(),
                        customer.companyName(),
                        customer.names(),
                        customer.taxScheme().toDomain(),
                        customer.fiscalResponsibilities().stream().map(ApiFiscalResponsibility::toDomain).toList(),
                        customer.email(),
                        customer.municipalityCode(),
                        customer.address(),
                        customer.phone()),
                request.lines().stream().map(InvoiceRestMapper::toCommandLine).toList(),
                request.paymentMethod().toDomain(),
                switch (request.paymentTerms().type()) {
                    case CASH -> new CreateInvoiceCommand.CashTerms();
                    case CREDIT -> new CreateInvoiceCommand.CreditTerms(request.paymentTerms().dueDate());
                },
                request.observation());
    }

    private static CreateInvoiceCommand.Line toCommandLine(CreateInvoiceRequest.Line line) {
        return new CreateInvoiceCommand.Line(
                line.code(),
                line.name(),
                line.quantity(),
                line.unitPrice(),
                line.discountRate(),
                line.unit().toDomain(),
                Boolean.TRUE.equals(line.ivaExcluded()),
                line.taxes().stream()
                        .map(tax -> new CreateInvoiceCommand.Tax(tax.type().toDomain(), tax.rate()))
                        .toList());
    }

    static InvoiceResponse toResponse(Invoice invoice) {
        CustomerSnapshot customer = invoice.customer();
        return new InvoiceResponse(
                invoice.id().value(),
                invoice.referenceCode().value(),
                ApiInvoiceStatus.from(invoice.status()),
                invoice.issueDate(),
                new InvoiceResponse.Customer(
                        ApiDocumentType.from(customer.identification().type()),
                        customer.identification().number(),
                        customer.identification().checkDigit(),
                        ApiPersonType.from(customer.personType()),
                        customer.companyName(),
                        customer.names(),
                        ApiTaxScheme.from(customer.fiscalProfile().taxScheme()),
                        customer.fiscalProfile().responsibilities().stream().sorted()
                                .map(ApiFiscalResponsibility::from).toList(),
                        customer.email(),
                        customer.optionalMunicipality().map(Municipality::code).orElse(null),
                        customer.address(),
                        customer.optionalPhone().map(PhoneNumber::value).orElse(null)),
                ApiPaymentMethod.from(invoice.paymentMethod()),
                switch (invoice.paymentTerms()) {
                    case PaymentTerms.Cash _ -> new InvoiceResponse.PaymentTerms(ApiPaymentTermsType.CASH, null);
                    case PaymentTerms.Credit(var dueDate) ->
                            new InvoiceResponse.PaymentTerms(ApiPaymentTermsType.CREDIT, dueDate);
                },
                invoice.observation().orElse(null),
                IntStream.range(0, invoice.lines().size())
                        .mapToObj(index -> toResponseLine(index + 1, invoice.lines().get(index)))
                        .toList(),
                toTotals(invoice),
                invoice.electronicDocument()
                        .map(document -> new InvoiceResponse.ElectronicDocument(
                                document.number(),
                                document.cufe(),
                                document.pdfUrl().toString(),
                                document.validatedAt(),
                                amount(document.officialTotal()),
                                invoice.hasOfficialTotalDiscrepancy()))
                        .orElse(null),
                invoice.rejectionReason().orElse(null));
    }

    private static InvoiceResponse.Line toResponseLine(int lineNumber, InvoiceLine line) {
        return new InvoiceResponse.Line(
                lineNumber,
                line.code(),
                line.name(),
                line.quantity().value(),
                line.unitPrice().amount(),
                line.discountRate().percent(),
                ApiUnitOfMeasure.from(line.unit()),
                line.excluded(),
                line.taxes().stream()
                        .map(tax -> new InvoiceResponse.LineTax(ApiTaxType.from(tax.type()), tax.rate().percent(),
                                amount(line.taxAmount(tax.type()))))
                        .toList(),
                amount(line.grossAmount()),
                amount(line.discountAmount()),
                amount(line.taxableAmount()),
                amount(line.taxAmount()),
                amount(line.total()));
    }

    /** El desglose incluye solo los tipos de impuesto que aparecen en alguna línea, en el orden del enum. */
    private static InvoiceResponse.Totals toTotals(Invoice invoice) {
        List<InvoiceResponse.TaxTotal> taxes = Arrays.stream(TaxType.values())
                .filter(type -> invoice.lines().stream()
                        .anyMatch(line -> line.taxes().stream().anyMatch(tax -> tax.type() == type)))
                .map(type -> new InvoiceResponse.TaxTotal(ApiTaxType.from(type), amount(invoice.totalTax(type))))
                .toList();
        return new InvoiceResponse.Totals(
                amount(invoice.subtotal()),
                amount(invoice.totalDiscount()),
                taxes,
                amount(invoice.totalTax()),
                amount(invoice.total()));
    }

    private static BigDecimal amount(Money money) {
        return money.amount();
    }
}
