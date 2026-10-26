package com.facturaflow.sales.application.service;

import com.facturaflow.sales.application.exception.CatalogUnavailableException;
import com.facturaflow.sales.application.port.out.CatalogClient;
import com.facturaflow.sales.application.port.out.CatalogCustomer;
import com.facturaflow.sales.application.port.out.CatalogProduct;
import com.facturaflow.sales.domain.model.CustomerDocumentRef;
import com.facturaflow.sales.domain.model.CustomerSnapshot;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Catalog falso en memoria: clientes y productos que el test registra, y un interruptor de "caído". */
public class InMemoryCatalog implements CatalogClient {

    private final Map<String, CatalogCustomer> customers = new HashMap<>();
    private final Map<String, CatalogProduct> products = new HashMap<>();
    private final List<Set<String>> productLookups = new ArrayList<>();
    private int customerLookups;
    private boolean down;

    InMemoryCatalog withCustomer(CustomerSnapshot snapshot, boolean active) {
        customers.put(snapshot.documentType() + ":" + snapshot.documentNumber(), new CatalogCustomer(snapshot, active));
        return this;
    }

    InMemoryCatalog withProduct(CatalogProduct product) {
        products.put(product.code().toLowerCase(Locale.ROOT), product);
        return this;
    }

    void goDown() {
        down = true;
    }

    @Override
    public Optional<CatalogCustomer> findCustomer(CustomerDocumentRef document) {
        failIfDown();
        customerLookups++;
        return Optional.ofNullable(customers.get(document.type() + ":" + document.number()));
    }

    @Override
    public List<CatalogProduct> findProducts(Set<String> codes) {
        failIfDown();
        productLookups.add(Set.copyOf(codes));
        return codes.stream().map(code -> products.get(code.toLowerCase(Locale.ROOT)))
                .filter(product -> product != null).distinct().toList();
    }

    int calls() {
        return customerLookups + productLookups.size();
    }

    List<Set<String>> productLookups() {
        return productLookups;
    }

    private void failIfDown() {
        if (down) {
            throw new CatalogUnavailableException("catalog caído (test)", CatalogUnavailableException.Reason.FAILURE,
                    Duration.ofSeconds(5), null);
        }
    }
}
