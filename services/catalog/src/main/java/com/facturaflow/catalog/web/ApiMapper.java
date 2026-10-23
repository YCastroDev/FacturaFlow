package com.facturaflow.catalog.web;

import com.facturaflow.catalog.model.Customer;
import com.facturaflow.catalog.model.CustomerDocument;
import com.facturaflow.catalog.model.CustomerProfile;
import com.facturaflow.catalog.model.DocumentType;
import com.facturaflow.catalog.model.FiscalResponsibility;
import com.facturaflow.catalog.model.PersonType;
import com.facturaflow.catalog.model.Product;
import com.facturaflow.catalog.model.ProductDetails;
import com.facturaflow.catalog.model.ProductTax;
import com.facturaflow.catalog.model.TaxScheme;
import com.facturaflow.catalog.model.TaxType;
import com.facturaflow.catalog.model.UnitOfMeasure;
import com.facturaflow.catalog.web.dto.ApiDocumentType;
import com.facturaflow.catalog.web.dto.ApiFiscalResponsibility;
import com.facturaflow.catalog.web.dto.ApiPersonType;
import com.facturaflow.catalog.web.dto.ApiTaxScheme;
import com.facturaflow.catalog.web.dto.ApiTaxType;
import com.facturaflow.catalog.web.dto.ApiUnitOfMeasure;
import com.facturaflow.catalog.web.dto.CustomerRequest;
import com.facturaflow.catalog.web.dto.CustomerResponse;
import com.facturaflow.catalog.web.dto.PageResponse;
import com.facturaflow.catalog.web.dto.ProductRequest;
import com.facturaflow.catalog.web.dto.ProductResponse;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;

/**
 * Traducción entre los DTOs de la API y el modelo. Los controladores leen las entidades, nunca las
 * serializan.
 *
 * <p>Los enums de la API y los del modelo tienen los mismos nombres (y los mismos que la API de
 * invoicing, para que sales pueda pasarlos tal cual), así que se traducen por nombre; {@code
 * ApiMapperTest} comprueba que cada valor tenga su par en los dos sentidos, así un enum que cambie
 * solo en un lado rompe el build y no una petición.
 */
final class ApiMapper {

    private ApiMapper() {
    }

    // ----- Productos -----

    static ProductDetails details(ProductRequest request) {
        List<ProductTax> taxes = request.taxes().stream()
                .map(tax -> new ProductTax(convert(tax.type(), TaxType.class), tax.rate()))
                .toList();
        return new ProductDetails(request.name(), request.unitPrice(), convert(request.unit(), UnitOfMeasure.class),
                Boolean.TRUE.equals(request.ivaExcluded()), taxes);
    }

    static ProductResponse response(Product product) {
        ProductDetails details = product.details();
        List<ProductResponse.Tax> taxes = details.taxes().stream()
                .map(tax -> new ProductResponse.Tax(convert(tax.type(), ApiTaxType.class), tax.rate()))
                .toList();
        return new ProductResponse(product.id(), product.code(), details.name(), details.unitPrice(),
                convert(details.unit(), ApiUnitOfMeasure.class), details.ivaExcluded(), taxes, product.active(),
                product.version(), product.createdAt(), product.updatedAt());
    }

    // ----- Clientes -----

    static CustomerDocument document(CustomerRequest request) {
        return new CustomerDocument(convert(request.documentType(), DocumentType.class), request.documentNumber(),
                request.checkDigit());
    }

    static CustomerProfile profile(CustomerRequest request) {
        Set<FiscalResponsibility> responsibilities = request.fiscalResponsibilities().stream()
                .map(value -> convert(value, FiscalResponsibility.class))
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(FiscalResponsibility.class)));
        return new CustomerProfile(convert(request.personType(), PersonType.class), request.companyName(),
                request.names(), convert(request.taxScheme(), TaxScheme.class), responsibilities,
                request.municipalityCode(), request.address(), request.phone(), request.email());
    }

    static CustomerResponse response(Customer customer) {
        CustomerDocument document = customer.document();
        CustomerProfile profile = customer.profile();
        List<ApiFiscalResponsibility> responsibilities = profile.fiscalResponsibilities().stream()
                .map(value -> convert(value, ApiFiscalResponsibility.class))
                .toList();
        return new CustomerResponse(customer.id(), convert(document.type(), ApiDocumentType.class), document.number(),
                document.checkDigit(), convert(profile.personType(), ApiPersonType.class), profile.companyName(),
                profile.names(), profile.displayName(), convert(profile.taxScheme(), ApiTaxScheme.class),
                responsibilities, profile.municipalityCode(), profile.address(), profile.phone(), profile.email(),
                customer.active(), customer.version(), customer.createdAt(), customer.updatedAt());
    }

    static DocumentType documentType(ApiDocumentType type) {
        return convert(type, DocumentType.class);
    }

    // ----- Comunes -----

    static <E, R> PageResponse<R> page(Page<E> page, Function<E, R> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    /** Traducción por nombre entre un enum de la API y uno del modelo (en cualquier sentido). */
    static <T extends Enum<T>> T convert(Enum<?> value, Class<T> target) {
        return Enum.valueOf(target, value.name());
    }
}
