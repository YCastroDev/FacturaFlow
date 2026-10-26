package com.facturaflow.sales.infrastructure.adapter.out.catalog;

import com.facturaflow.sales.application.port.out.CatalogClient;
import com.facturaflow.sales.application.port.out.CatalogCustomer;
import com.facturaflow.sales.application.port.out.CatalogProduct;
import com.facturaflow.sales.domain.model.CustomerDocumentRef;
import com.facturaflow.sales.domain.model.CustomerSnapshot;
import com.facturaflow.sales.domain.model.DocumentType;
import com.facturaflow.sales.domain.model.FiscalResponsibility;
import com.facturaflow.sales.domain.model.LineTax;
import com.facturaflow.sales.domain.model.Money;
import com.facturaflow.sales.domain.model.PersonType;
import com.facturaflow.sales.domain.model.TaxRate;
import com.facturaflow.sales.domain.model.TaxScheme;
import com.facturaflow.sales.domain.model.TaxType;
import com.facturaflow.sales.domain.model.UnitOfMeasure;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;

/**
 * Implementa {@link CatalogClient} sobre la API REST de catalog (ADR 0011): 1 llamada por el cliente y 1
 * por TODOS los productos (consulta por lote). Traduce los DTOs de catalog al dominio de sales; los
 * nombres de los enums son los mismos en los tres servicios.
 *
 * <p>Un 404 del cliente es "no existe" (no una falla). Cualquier otro estado inesperado (p. ej. un 400
 * porque los dos servicios no están de acuerdo en el contrato) es un error de programación: sube como
 * {@link IllegalStateException} (500) sin contar en el circuito.
 */
final class CatalogRestClient implements CatalogClient {

    private static final String CUSTOMER_BY_DOCUMENT = "/api/v1/customers/by-document/{type}/{number}";
    private static final String PRODUCT_LOOKUP = "/api/v1/products/lookup";

    private final CatalogHttp http;

    CatalogRestClient(CatalogHttp http) {
        this.http = http;
    }

    @Override
    public Optional<CatalogCustomer> findCustomer(CustomerDocumentRef document) {
        CatalogResponse response = http.exchange("Consulta del cliente " + document.type() + " " + document.number(),
                client -> client.get().uri(CUSTOMER_BY_DOCUMENT, document.type().name(), document.number()));
        if (response.status() == 404) {
            return Optional.empty();
        }
        CatalogDtos.Customer customer = read(response, CatalogDtos.Customer.class, "el cliente");
        return Optional.of(new CatalogCustomer(toSnapshot(customer), customer.active()));
    }

    @Override
    public List<CatalogProduct> findProducts(Set<String> codes) {
        CatalogResponse response = http.exchange("Consulta por lote de " + codes.size() + " productos",
                client -> client.post().uri(PRODUCT_LOOKUP).contentType(MediaType.APPLICATION_JSON)
                        .body(CatalogJson.MAPPER.writeValueAsBytes(new CatalogDtos.LookupRequest(List.copyOf(codes)))));
        CatalogDtos.LookupResponse lookup = read(response, CatalogDtos.LookupResponse.class, "los productos");
        return lookup.items().stream().map(CatalogRestClient::toProduct).toList();
    }

    private static <T> T read(CatalogResponse response, Class<T> type, String what) {
        if (response.status() != 200) {
            throw new IllegalStateException("Respuesta inesperada de catalog al consultar " + what + ": HTTP "
                    + response.status());
        }
        return CatalogJson.MAPPER.readValue(response.body(), type);
    }

    private static CustomerSnapshot toSnapshot(CatalogDtos.Customer customer) {
        Set<FiscalResponsibility> responsibilities = customer.fiscalResponsibilities() == null ? Set.of()
                : customer.fiscalResponsibilities().stream().map(FiscalResponsibility::valueOf)
                        .collect(Collectors.toCollection(() -> EnumSet.noneOf(FiscalResponsibility.class)));
        return new CustomerSnapshot(customer.id(), DocumentType.valueOf(customer.documentType()),
                customer.documentNumber(), customer.checkDigit(), PersonType.valueOf(customer.personType()),
                customer.companyName(), customer.names(), TaxScheme.valueOf(customer.taxScheme()), responsibilities,
                customer.municipalityCode(), customer.address(), customer.phone(), customer.email());
    }

    private static CatalogProduct toProduct(CatalogDtos.Product product) {
        List<LineTax> taxes = product.taxes() == null ? List.of() : product.taxes().stream()
                .map(tax -> new LineTax(TaxType.valueOf(tax.type()), new TaxRate(tax.rate())))
                .toList();
        return new CatalogProduct(product.id(), product.code(), product.name(), Money.of(product.unitPrice()),
                UnitOfMeasure.valueOf(product.unit()), product.ivaExcluded(), taxes, product.active());
    }
}
