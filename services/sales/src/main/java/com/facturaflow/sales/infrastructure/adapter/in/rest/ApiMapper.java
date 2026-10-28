package com.facturaflow.sales.infrastructure.adapter.in.rest;

import com.facturaflow.sales.application.port.in.CreateSaleCommand;
import com.facturaflow.sales.application.port.out.SalePage;
import com.facturaflow.sales.domain.model.CustomerSnapshot;
import com.facturaflow.sales.domain.model.DocumentType;
import com.facturaflow.sales.domain.model.InvoicingOutcome;
import com.facturaflow.sales.domain.model.PaymentMethod;
import com.facturaflow.sales.domain.model.PaymentTerms;
import com.facturaflow.sales.domain.model.Sale;
import com.facturaflow.sales.domain.model.SaleLine;
import com.facturaflow.sales.domain.model.TaxType;
import com.facturaflow.sales.infrastructure.adapter.in.rest.dto.ApiDocumentType;
import com.facturaflow.sales.infrastructure.adapter.in.rest.dto.ApiFiscalResponsibility;
import com.facturaflow.sales.infrastructure.adapter.in.rest.dto.ApiPaymentMethod;
import com.facturaflow.sales.infrastructure.adapter.in.rest.dto.ApiPaymentTermsType;
import com.facturaflow.sales.infrastructure.adapter.in.rest.dto.ApiPersonType;
import com.facturaflow.sales.infrastructure.adapter.in.rest.dto.ApiSaleStatus;
import com.facturaflow.sales.infrastructure.adapter.in.rest.dto.ApiTaxScheme;
import com.facturaflow.sales.infrastructure.adapter.in.rest.dto.ApiTaxType;
import com.facturaflow.sales.infrastructure.adapter.in.rest.dto.ApiUnitOfMeasure;
import com.facturaflow.sales.infrastructure.adapter.in.rest.dto.CreateSaleRequest;
import com.facturaflow.sales.infrastructure.adapter.in.rest.dto.PageResponse;
import com.facturaflow.sales.infrastructure.adapter.in.rest.dto.SaleResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * Traducción entre los DTOs de la API y el dominio. El controlador lee el agregado, nunca lo serializa.
 *
 * <p>Los enums de la API y del dominio tienen los mismos nombres (y los mismos que catalog e invoicing),
 * así que se traducen por nombre; {@code ApiMapperTest} comprueba que cada valor tenga su par en los dos
 * sentidos (como en catalog, ADR 0011): un enum que cambie solo en un lado rompe el build, no una petición.
 */
final class ApiMapper {

    private ApiMapper() {
    }

    /**
     * @param soldByUserId   el {@code sub} del token de quien vende
     * @param soldByUsername su {@code preferred_username}
     */
    static CreateSaleCommand command(CreateSaleRequest request, String soldByUserId, String soldByUsername) {
        List<CreateSaleCommand.Line> lines = request.lines().stream()
                .map(line -> new CreateSaleCommand.Line(line.productCode(), line.quantity(), line.discountRate()))
                .toList();
        CreateSaleCommand.Terms terms = switch (request.paymentTerms().type()) {
            case CASH -> new CreateSaleCommand.CashTerms();
            case CREDIT -> new CreateSaleCommand.CreditTerms(request.paymentTerms().dueDate());
        };
        return new CreateSaleCommand(request.referenceCode(),
                convert(request.customer().documentType(), DocumentType.class), request.customer().documentNumber(),
                lines, convert(request.paymentMethod(), PaymentMethod.class), terms, request.observation(),
                soldByUserId, soldByUsername);
    }

    static SaleResponse response(Sale sale) {
        CustomerSnapshot customer = sale.customer();
        SaleResponse.Customer customerDto = new SaleResponse.Customer(customer.customerId(),
                convert(customer.documentType(), ApiDocumentType.class), customer.documentNumber(),
                customer.checkDigit(), convert(customer.personType(), ApiPersonType.class), customer.companyName(),
                customer.names(), customer.displayName(), convert(customer.taxScheme(), ApiTaxScheme.class),
                customer.fiscalResponsibilities().stream().map(value -> convert(value, ApiFiscalResponsibility.class))
                        .toList(),
                customer.municipalityCode(), customer.address(), customer.phone(), customer.email());
        List<SaleResponse.Line> lines = new ArrayList<>();
        for (int i = 0; i < sale.lines().size(); i++) {
            lines.add(line(i + 1, sale.lines().get(i)));
        }
        SaleResponse.PaymentTerms terms = switch (sale.paymentTerms()) {
            case PaymentTerms.Cash ignored -> new SaleResponse.PaymentTerms(ApiPaymentTermsType.CASH, null);
            case PaymentTerms.Credit credit -> new SaleResponse.PaymentTerms(ApiPaymentTermsType.CREDIT, credit.dueDate());
        };
        SaleResponse.Totals totals = new SaleResponse.Totals(sale.subtotal().amount(), sale.totalDiscount().amount(),
                sale.totalTax(TaxType.IVA).amount(), sale.totalTax(TaxType.INC).amount(), sale.totalTax().amount(),
                sale.total().amount());
        SaleResponse.Invoice invoice = null;
        SaleResponse.InvoiceFailure failure = null;
        switch (sale.invoicing().orElse(null)) {
            case null -> {
            }
            case InvoicingOutcome.Invoiced invoiced -> invoice = new SaleResponse.Invoice(
                    invoiced.invoice().invoiceId(), invoiced.invoice().number(), invoiced.invoice().cufe(), invoiced.at());
            case InvoicingOutcome.Failed failed -> failure = new SaleResponse.InvoiceFailure(failed.reason(),
                    failed.invoiceId(), failed.at());
        }
        SaleResponse.SoldBy soldBy = sale.soldBy()
                .map(seller -> new SaleResponse.SoldBy(seller.userId(), seller.username())).orElse(null);
        return new SaleResponse(sale.id().value(), sale.referenceCode().value(),
                convert(sale.status(), ApiSaleStatus.class), sale.saleDate(), sale.createdAt(), soldBy, customerDto,
                lines,
                convert(sale.paymentMethod(), ApiPaymentMethod.class), terms, sale.observation().orElse(null), totals,
                invoice, failure);
    }

    private static SaleResponse.Line line(int number, SaleLine line) {
        List<SaleResponse.Tax> taxes = line.taxes().stream()
                .map(tax -> new SaleResponse.Tax(convert(tax.type(), ApiTaxType.class), tax.rate().percent(),
                        line.taxAmount(tax.type()).amount()))
                .toList();
        return new SaleResponse.Line(number, line.productId(), line.code(), line.name(),
                convert(line.unit(), ApiUnitOfMeasure.class), line.quantity().value(), line.unitPrice().amount(),
                line.discountRate().percent(), line.ivaExcluded(), taxes, line.grossAmount().amount(),
                line.discountAmount().amount(), line.taxableAmount().amount(), line.taxAmount().amount(),
                line.total().amount());
    }

    static PageResponse<SaleResponse> page(SalePage page) {
        return new PageResponse<>(page.items().stream().map(ApiMapper::response).toList(), page.page(), page.size(),
                page.totalItems(), page.totalPages());
    }

    /** Traducción por nombre entre un enum de la API y uno del dominio (en cualquier sentido). */
    static <T extends Enum<T>> T convert(Enum<?> value, Class<T> target) {
        return value == null ? null : Enum.valueOf(target, value.name());
    }
}
