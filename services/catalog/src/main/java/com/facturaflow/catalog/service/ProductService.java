package com.facturaflow.catalog.service;

import com.facturaflow.catalog.model.Product;
import com.facturaflow.catalog.model.ProductDetails;
import com.facturaflow.catalog.repository.CatalogSearch;
import com.facturaflow.catalog.repository.ProductRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Casos de uso de los productos. Cada método es una transacción (en catalog no hay llamadas externas
 * que dejar fuera de ella; ADR 0011). Devuelve las entidades con sus colecciones ya cargadas: la capa
 * web las lee fuera de la transacción, sin cargas perezosas.
 */
@Service
public class ProductService {

    private final ProductRepository products;
    private final Clock clock;

    public ProductService(ProductRepository products, Clock clock) {
        this.products = products;
        this.clock = clock;
    }

    /** 409 si el código ya existe (sin distinguir mayúsculas), incluso si dos peticiones llegan a la vez. */
    @Transactional
    public Product create(String code, ProductDetails details) {
        String normalized = Product.normalizeCode(code);
        if (products.findByCodeIgnoringCase(normalized).isPresent()) {
            throw new DuplicateProductCodeException(normalized);
        }
        Product product = new Product(normalized, details, clock.instant());
        try {
            return products.saveAndFlush(product);
        } catch (DataIntegrityViolationException e) {
            if (UniqueViolations.isViolationOf(e, ProductRepository.UNIQUE_CODE)) {
                throw new DuplicateProductCodeException(normalized);
            }
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public Product get(UUID id) {
        return products.findById(id)
                .orElseThrow(() -> new ProductNotFoundException("No existe un producto con id " + id + "."));
    }

    @Transactional(readOnly = true)
    public Product getByCode(String code) {
        String normalized = code == null ? "" : code.strip();
        return products.findByCodeIgnoringCase(normalized)
                .orElseThrow(() -> new ProductNotFoundException("No existe un producto con el código '"
                        + normalized + "'."));
    }

    /** {@code text} y {@code active} nulos = sin ese filtro. */
    @Transactional(readOnly = true)
    public Page<Product> search(String text, Boolean active, CatalogPage page) {
        return products.findAll(CatalogSearch.products(text, active), PageRequest.of(page.number(), page.size()));
    }

    /**
     * Reemplaza los datos editables si {@code expectedVersion} (el If-Match) sigue siendo la versión
     * actual; si no, 412. Un PUT sin cambios no sube la versión.
     */
    @Transactional
    public Product update(UUID id, long expectedVersion, String code, ProductDetails details) {
        Product product = get(id);
        if (product.version() != expectedVersion) {
            throw new StaleVersionException(subject(product));
        }
        if (product.update(code, details, clock.instant())) {
            flush(product, true);
        }
        return product;
    }

    /** Idempotente. {@code expectedVersion} es opcional: nulo = sin If-Match. */
    @Transactional
    public Product deactivate(UUID id, Long expectedVersion) {
        Product product = getChecked(id, expectedVersion);
        if (product.deactivate(clock.instant())) {
            flush(product, expectedVersion != null);
        }
        return product;
    }

    /** Idempotente. {@code expectedVersion} es opcional: nulo = sin If-Match. */
    @Transactional
    public Product activate(UUID id, Long expectedVersion) {
        Product product = getChecked(id, expectedVersion);
        if (product.activate(clock.instant())) {
            flush(product, expectedVersion != null);
        }
        return product;
    }

    private Product getChecked(UUID id, Long expectedVersion) {
        Product product = get(id);
        if (expectedVersion != null && !expectedVersion.equals(product.version())) {
            throw new StaleVersionException(subject(product));
        }
        return product;
    }

    /**
     * El UPDATE lleva {@code WHERE version = <leída>}: si otra transacción la cambió entre la lectura y
     * aquí, no actualiza ninguna fila y JPA lanza la excepción de bloqueo optimista. Se hace flush aquí
     * (y no al confirmar) para poder traducirla.
     */
    private void flush(Product product, boolean withPrecondition) {
        try {
            products.saveAndFlush(product);
        } catch (OptimisticLockingFailureException e) {
            throw withPrecondition ? new StaleVersionException(subject(product))
                    : new ConcurrentUpdateException(subject(product));
        }
    }

    private static String subject(Product product) {
        return "El producto " + product.code();
    }
}
