package com.facturaflow.sales.infrastructure.adapter.out.persistence;

import com.facturaflow.sales.application.port.out.RequestHash;
import com.facturaflow.sales.application.port.out.StoredSale;
import com.facturaflow.sales.domain.model.CustomerSnapshot;
import com.facturaflow.sales.domain.model.DiscountRate;
import com.facturaflow.sales.domain.model.DocumentType;
import com.facturaflow.sales.domain.model.FiscalResponsibility;
import com.facturaflow.sales.domain.model.LineTax;
import com.facturaflow.sales.domain.model.Money;
import com.facturaflow.sales.domain.model.PaymentMethod;
import com.facturaflow.sales.domain.model.PaymentTerms;
import com.facturaflow.sales.domain.model.PersonType;
import com.facturaflow.sales.domain.model.Quantity;
import com.facturaflow.sales.domain.model.ReferenceCode;
import com.facturaflow.sales.domain.model.Sale;
import com.facturaflow.sales.domain.model.SaleId;
import com.facturaflow.sales.domain.model.SaleLine;
import com.facturaflow.sales.domain.model.SaleStatus;
import com.facturaflow.sales.domain.model.TaxRate;
import com.facturaflow.sales.domain.model.TaxScheme;
import com.facturaflow.sales.domain.model.TaxType;
import com.facturaflow.sales.domain.model.UnitOfMeasure;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Mapeo a mano entre el agregado {@link Sale} y sus filas (como en invoicing, ADR 0005). La venta se
 * reconstruye con {@link Sale#reconstitute}, nunca saltándose sus validaciones.
 */
@Component
class SalePersistenceMapper {

    SaleEntity toNewEntity(Sale sale, RequestHash requestHash) {
        SaleEntity entity = new SaleEntity();
        entity.id = sale.id().value();
        entity.referenceCode = sale.referenceCode().value();
        entity.requestHash = requestHash.value();
        entity.status = sale.status().name();
        entity.saleDate = sale.saleDate();
        entity.createdAt = sale.createdAt();
        entity.paymentMethod = sale.paymentMethod().name();
        switch (sale.paymentTerms()) {
            case PaymentTerms.Cash ignored -> entity.paymentType = "CASH";
            case PaymentTerms.Credit credit -> {
                entity.paymentType = "CREDIT";
                entity.dueDate = credit.dueDate();
            }
        }
        entity.observation = sale.observation().orElse(null);
        CustomerSnapshot customer = sale.customer();
        entity.customerId = customer.customerId();
        entity.customerDocumentType = customer.documentType().name();
        entity.customerDocumentNumber = customer.documentNumber();
        entity.customerCheckDigit = customer.checkDigit();
        entity.customerPersonType = customer.personType().name();
        entity.customerCompanyName = customer.companyName();
        entity.customerNames = customer.names();
        entity.customerTaxScheme = customer.taxScheme().name();
        entity.customerFiscalResponsibilities = customer.fiscalResponsibilities().stream()
                .map(Enum::name).sorted().toArray(String[]::new);
        entity.customerMunicipalityCode = customer.municipalityCode();
        entity.customerAddress = customer.address();
        entity.customerPhone = customer.phone();
        entity.customerEmail = customer.email();
        sale.lines().forEach(line -> entity.lines.add(toEntity(line)));
        return entity;
    }

    StoredSale toStored(SaleEntity entity) {
        return new StoredSale(toDomain(entity), new RequestHash(entity.requestHash));
    }

    Sale toDomain(SaleEntity entity) {
        CustomerSnapshot customer = new CustomerSnapshot(entity.customerId,
                DocumentType.valueOf(entity.customerDocumentType), entity.customerDocumentNumber,
                entity.customerCheckDigit, PersonType.valueOf(entity.customerPersonType), entity.customerCompanyName,
                entity.customerNames, TaxScheme.valueOf(entity.customerTaxScheme),
                Arrays.stream(entity.customerFiscalResponsibilities).map(FiscalResponsibility::valueOf)
                        .collect(Collectors.toCollection(() -> EnumSet.noneOf(FiscalResponsibility.class))),
                entity.customerMunicipalityCode, entity.customerAddress, entity.customerPhone, entity.customerEmail);
        PaymentTerms terms = switch (entity.paymentType) {
            case "CASH" -> PaymentTerms.cash();
            case "CREDIT" -> PaymentTerms.credit(entity.dueDate);
            default -> throw new IllegalStateException("Forma de pago desconocida en la base: " + entity.paymentType);
        };
        return Sale.reconstitute(new SaleId(entity.id), new ReferenceCode(entity.referenceCode), entity.saleDate,
                entity.createdAt, customer, entity.lines.stream().map(this::toDomain).toList(),
                PaymentMethod.valueOf(entity.paymentMethod), terms, entity.observation,
                SaleStatus.valueOf(entity.status), entity.version);
    }

    private SaleLineEntity toEntity(SaleLine line) {
        SaleLineEntity entity = new SaleLineEntity();
        entity.id = UUID.randomUUID();
        entity.productId = line.productId();
        entity.code = line.code();
        entity.name = line.name();
        entity.quantity = line.quantity().value();
        entity.unitPrice = line.unitPrice().amount();
        entity.discountRate = line.discountRate().percent();
        entity.unitOfMeasure = line.unit().name();
        entity.ivaExcluded = line.ivaExcluded();
        line.taxes().forEach(tax -> entity.taxes.add(new LineTaxEmbeddable(tax.type().name(), tax.rate().percent())));
        return entity;
    }

    private SaleLine toDomain(SaleLineEntity entity) {
        return new SaleLine(entity.productId, entity.code, entity.name, new Quantity(entity.quantity),
                Money.of(entity.unitPrice), new DiscountRate(entity.discountRate),
                entity.taxes.stream().map(tax -> new LineTax(TaxType.valueOf(tax.taxType), new TaxRate(tax.rate)))
                        .toList(),
                UnitOfMeasure.valueOf(entity.unitOfMeasure), entity.ivaExcluded);
    }
}
