package com.facturaflow.sales.application.service;

import com.facturaflow.sales.application.exception.DuplicateReferenceCodeException;
import com.facturaflow.sales.application.exception.InvalidCatalogReferencesException;
import com.facturaflow.sales.application.exception.ReferenceCodeConflictException;
import com.facturaflow.sales.application.port.in.CreateSaleCommand;
import com.facturaflow.sales.application.port.in.CreateSaleResult;
import com.facturaflow.sales.application.port.in.CreateSaleUseCase;
import com.facturaflow.sales.application.port.out.CatalogClient;
import com.facturaflow.sales.application.port.out.CatalogCustomer;
import com.facturaflow.sales.application.port.out.CatalogProduct;
import com.facturaflow.sales.application.port.out.RequestHash;
import com.facturaflow.sales.application.port.out.SaleRepository;
import com.facturaflow.sales.application.port.out.StoredSale;
import com.facturaflow.sales.domain.model.CustomerDocumentRef;
import com.facturaflow.sales.domain.model.Sale;
import com.facturaflow.sales.domain.model.SaleId;
import com.facturaflow.sales.domain.model.SaleLine;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Confirma ventas de forma idempotente por código de referencia (ADR 0012), con el mismo esquema que la
 * creación de facturas en invoicing:
 * <ol>
 *   <li>se valida y normaliza la petición, y se calcula su huella;</li>
 *   <li>si el código ya existe: misma huella → la venta existente (200), otra huella → 409. Esto ocurre
 *       ANTES de consultar a catalog: un reintento funciona aunque catalog esté caído;</li>
 *   <li>se consultan a catalog el cliente (1 llamada) y TODOS los productos (1 llamada por lote, sin N+1);
 *       inexistentes o inactivos → 422 con todos los problemas juntos;</li>
 *   <li>se confirma la venta con la copia de esos datos y se guarda. Si otra petición simultánea la creó
 *       entre el paso 2 y el INSERT, se relee la ganadora y se aplica la misma regla.</li>
 * </ol>
 * Sin transacción aquí: las llamadas a catalog ocurren fuera de la base (regla de ArchUnit).
 */
public final class CreateSaleService implements CreateSaleUseCase {

    private final SaleRepository repository;
    private final CatalogClient catalog;
    private final Clock clock;

    public CreateSaleService(SaleRepository repository, CatalogClient catalog, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CreateSaleResult create(CreateSaleCommand command) {
        SaleRequest request = SaleRequest.from(command);
        RequestHash requestHash = SaleRequestHasher.hash(request);

        Optional<StoredSale> existing = repository.findStoredByReferenceCode(request.referenceCode());
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), requestHash);
        }

        Optional<CatalogCustomer> customer = catalog.findCustomer(request.customer());
        Set<String> codes = request.lines().stream().map(SaleRequest.Line::productCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, CatalogProduct> products = catalog.findProducts(codes).stream()
                .collect(Collectors.toMap(product -> product.code().toLowerCase(Locale.ROOT), Function.identity(),
                        (first, second) -> first));
        verifyReferences(request, customer, products);

        List<SaleLine> lines = request.lines().stream()
                .map(line -> toSaleLine(line, products.get(line.key())))
                .toList();
        Sale sale = Sale.confirm(SaleId.generate(), request.referenceCode(), LocalDate.now(clock), clock.instant(),
                customer.orElseThrow().snapshot(), lines, request.paymentMethod(), request.paymentTerms(),
                request.observation());
        try {
            return CreateSaleResult.created(repository.saveNew(sale, requestHash));
        } catch (DuplicateReferenceCodeException race) {
            StoredSale winner = repository.findStoredByReferenceCode(request.referenceCode()).orElseThrow(() -> race);
            return replayOrConflict(winner, requestHash);
        }
    }

    private static void verifyReferences(SaleRequest request, Optional<CatalogCustomer> customer,
                                         Map<String, CatalogProduct> products) {
        CustomerDocumentRef document = request.customer();
        String customerProblem = null;
        if (customer.isEmpty()) {
            customerProblem = "No existe en catalog un cliente con " + document.type().description() + " "
                    + document.number() + ".";
        } else if (!customer.get().active()) {
            customerProblem = "El cliente con " + document.type().description() + " " + document.number()
                    + " está inactivo en catalog.";
        }
        Set<String> missing = new LinkedHashSet<>();
        Set<String> inactive = new LinkedHashSet<>();
        for (SaleRequest.Line line : request.lines()) {
            CatalogProduct product = products.get(line.key());
            if (product == null) {
                missing.add(line.productCode());
            } else if (!product.active()) {
                inactive.add(product.code());
            }
        }
        if (customerProblem != null || !missing.isEmpty() || !inactive.isEmpty()) {
            throw new InvalidCatalogReferencesException(customerProblem, new ArrayList<>(missing),
                    new ArrayList<>(inactive));
        }
    }

    /** Copia del producto de catalog + cantidad y descuento de la petición. */
    private static SaleLine toSaleLine(SaleRequest.Line line, CatalogProduct product) {
        return new SaleLine(product.id(), product.code(), product.name(), line.quantity(), product.unitPrice(),
                line.discountRate(), product.taxes(), product.unit(), product.ivaExcluded());
    }

    private static CreateSaleResult replayOrConflict(StoredSale existing, RequestHash requestHash) {
        if (existing.requestHash().equals(requestHash)) {
            return CreateSaleResult.replayed(existing.sale());
        }
        throw new ReferenceCodeConflictException(existing.sale().referenceCode().value());
    }
}
