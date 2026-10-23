package com.facturaflow.catalog.model;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Producto del catálogo. Su identidad es el código (único sin distinguir mayúsculas, inmutable); los
 * demás datos ({@link ProductDetails}) se reemplazan completos con {@link #update}. No se borra: se
 * desactiva, porque ventas y facturas lo referencian.
 *
 * <p>{@link #version()} es la versión del bloqueo optimista de JPA y la base del ETag de la API
 * (ADR 0011): solo cambia cuando cambia algún dato, así un PUT idéntico no invalida el ETag de nadie.
 */
@Entity
@Table(name = "products")
public class Product {

    public static final int MAX_CODE_LENGTH = 50;

    @Id
    private UUID id;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "unit_of_measure", nullable = false)
    private UnitOfMeasure unit;

    @Column(name = "iva_excluded", nullable = false)
    private boolean ivaExcluded;

    /** EAGER: siempre se necesitan para responder; en un listado se cargan por lotes (default_batch_fetch_size). */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "product_taxes", joinColumns = @JoinColumn(name = "product_id"))
    private List<ProductTax> taxes = new ArrayList<>();

    @Column(nullable = false)
    private boolean active;

    /** Nula hasta el primer INSERT: así Spring Data sabe que el producto es nuevo aunque ya tenga id. */
    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Product() {
        // JPA
    }

    public Product(String code, ProductDetails details, Instant now) {
        this.id = UUID.randomUUID();
        this.code = normalizeCode(code);
        apply(Objects.requireNonNull(details, "details"));
        this.active = true;
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    /** Código sin espacios a los lados; obligatorio y de hasta 50 caracteres (como en invoicing). */
    public static String normalizeCode(String code) {
        return Texts.required(code, "el código del producto", "código", MAX_CODE_LENGTH);
    }

    /**
     * Reemplaza los datos editables. {@code code} debe ser el mismo (exacto, tras quitar espacios): el
     * código no se cambia. Devuelve si algo cambió.
     */
    public boolean update(String code, ProductDetails details, Instant now) {
        if (!this.code.equals(normalizeCode(code))) {
            throw CatalogRuleException.immutable("el código del producto");
        }
        if (details().equals(details)) {
            return false;
        }
        apply(details);
        this.updatedAt = now;
        return true;
    }

    /** Idempotente: devuelve si cambió el estado. */
    public boolean deactivate(Instant now) {
        return changeActive(false, now);
    }

    /** Idempotente: devuelve si cambió el estado. */
    public boolean activate(Instant now) {
        return changeActive(true, now);
    }

    private boolean changeActive(boolean value, Instant now) {
        if (active == value) {
            return false;
        }
        active = value;
        updatedAt = now;
        return true;
    }

    private void apply(ProductDetails details) {
        this.name = details.name();
        this.unitPrice = details.unitPrice();
        this.unit = details.unit();
        this.ivaExcluded = details.ivaExcluded();
        this.taxes.clear();
        this.taxes.addAll(details.taxes());
    }

    public UUID id() {
        return id;
    }

    public String code() {
        return code;
    }

    public ProductDetails details() {
        return new ProductDetails(name, unitPrice, unit, ivaExcluded, List.copyOf(taxes));
    }

    public boolean active() {
        return active;
    }

    /** Versión del bloqueo optimista; {@code null} solo antes de guardarse por primera vez. */
    public Long version() {
        return version;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
