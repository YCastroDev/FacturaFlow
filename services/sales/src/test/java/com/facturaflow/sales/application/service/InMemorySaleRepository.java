package com.facturaflow.sales.application.service;

import com.facturaflow.sales.application.exception.DuplicateReferenceCodeException;
import com.facturaflow.sales.application.port.out.RequestHash;
import com.facturaflow.sales.application.port.out.SalePage;
import com.facturaflow.sales.application.port.out.SaleRepository;
import com.facturaflow.sales.application.port.out.StoredSale;
import com.facturaflow.sales.domain.model.ReferenceCode;
import com.facturaflow.sales.domain.model.Sale;
import com.facturaflow.sales.domain.model.SaleId;
import com.facturaflow.sales.domain.model.SaleStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Repositorio en memoria para los tests de los casos de uso (sin Spring ni base). */
public class InMemorySaleRepository implements SaleRepository {

    private final Map<String, StoredSale> byReference = new LinkedHashMap<>();
    /** Simula una carrera: el próximo saveNew falla como si otra petición hubiera guardado esta venta antes. */
    private StoredSale raceWinner;

    void loseNextRaceTo(StoredSale winner) {
        this.raceWinner = winner;
    }

    @Override
    public Sale saveNew(Sale sale, RequestHash requestHash) {
        if (raceWinner != null) {
            byReference.put(raceWinner.sale().referenceCode().value(), raceWinner);
            raceWinner = null;
            throw new DuplicateReferenceCodeException(sale.referenceCode().value(), null);
        }
        if (byReference.containsKey(sale.referenceCode().value())) {
            throw new DuplicateReferenceCodeException(sale.referenceCode().value(), null);
        }
        Sale saved = Sale.reconstitute(sale.id(), sale.referenceCode(), sale.saleDate(), sale.createdAt(),
                sale.customer(), sale.lines(), sale.paymentMethod(), sale.paymentTerms(),
                sale.observation().orElse(null), sale.status(), 0);
        byReference.put(sale.referenceCode().value(), new StoredSale(saved, requestHash));
        return saved;
    }

    @Override
    public Optional<Sale> findById(SaleId id) {
        return byReference.values().stream().map(StoredSale::sale).filter(sale -> sale.id().equals(id)).findFirst();
    }

    @Override
    public Optional<Sale> findByReferenceCode(ReferenceCode referenceCode) {
        return findStoredByReferenceCode(referenceCode).map(StoredSale::sale);
    }

    @Override
    public Optional<StoredSale> findStoredByReferenceCode(ReferenceCode referenceCode) {
        return Optional.ofNullable(byReference.get(referenceCode.value()));
    }

    @Override
    public SalePage findPage(SaleStatus status, int page, int size) {
        List<Sale> all = new ArrayList<>(byReference.values().stream().map(StoredSale::sale)
                .filter(sale -> status == null || sale.status() == status).toList());
        all.sort(Comparator.comparing(Sale::createdAt).reversed());
        int from = Math.min(page * size, all.size());
        int to = Math.min(from + size, all.size());
        return new SalePage(all.subList(from, to), page, size, all.size(), (all.size() + size - 1) / size);
    }

    int count() {
        return byReference.size();
    }
}
