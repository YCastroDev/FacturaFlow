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
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Cliente del catálogo. Su identidad es el documento ({@link CustomerDocument}: tipo + número, único e
 * inmutable); los demás datos ({@link CustomerProfile}) se reemplazan completos con {@link #update}. No
 * se borra: se desactiva, porque ventas y facturas lo referencian.
 *
 * <p>{@link #version()} es la versión del bloqueo optimista y la base del ETag de la API (ADR 0011).
 */
@Entity
@Table(name = "customers")
public class Customer {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false)
    private DocumentType documentType;

    @Column(name = "document_number", nullable = false)
    private String documentNumber;

    @Column(name = "check_digit")
    private Integer checkDigit;

    @Enumerated(EnumType.STRING)
    @Column(name = "person_type", nullable = false)
    private PersonType personType;

    @Column(name = "company_name")
    private String companyName;

    private String names;

    @Enumerated(EnumType.STRING)
    @Column(name = "tax_scheme", nullable = false)
    private TaxScheme taxScheme;

    /** EAGER: siempre se necesitan para responder; en un listado se cargan por lotes (default_batch_fetch_size). */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "customer_fiscal_responsibilities", joinColumns = @JoinColumn(name = "customer_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "responsibility", nullable = false)
    private Set<FiscalResponsibility> fiscalResponsibilities = new HashSet<>();

    @Column(name = "municipality_code")
    private String municipalityCode;

    private String address;

    private String phone;

    private String email;

    @Column(nullable = false)
    private boolean active;

    /** Nula hasta el primer INSERT: así Spring Data sabe que el cliente es nuevo aunque ya tenga id. */
    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Customer() {
        // JPA
    }

    public Customer(CustomerDocument document, CustomerProfile profile, Instant now) {
        Objects.requireNonNull(document, "document");
        this.id = UUID.randomUUID();
        this.documentType = document.type();
        this.documentNumber = document.number();
        this.checkDigit = document.checkDigit();
        apply(Objects.requireNonNull(profile, "profile"));
        this.active = true;
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    /**
     * Reemplaza los datos editables. {@code document} debe ser el mismo: el documento no se cambia.
     * Devuelve si algo cambió.
     */
    public boolean update(CustomerDocument document, CustomerProfile profile, Instant now) {
        if (!document().equals(document)) {
            throw CatalogRuleException.immutable("el documento del cliente");
        }
        if (profile().equals(profile)) {
            return false;
        }
        apply(profile);
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

    private void apply(CustomerProfile profile) {
        this.personType = profile.personType();
        this.companyName = profile.companyName();
        this.names = profile.names();
        this.taxScheme = profile.taxScheme();
        // Se modifica el conjunto (no se reemplaza): Hibernate solo borra e inserta las filas que cambian.
        this.fiscalResponsibilities.retainAll(profile.fiscalResponsibilities());
        this.fiscalResponsibilities.addAll(profile.fiscalResponsibilities());
        this.municipalityCode = profile.municipalityCode();
        this.address = profile.address();
        this.phone = profile.phone();
        this.email = profile.email();
    }

    public UUID id() {
        return id;
    }

    public CustomerDocument document() {
        return new CustomerDocument(documentType, documentNumber, checkDigit);
    }

    public CustomerProfile profile() {
        return new CustomerProfile(personType, companyName, names, taxScheme, EnumSet.copyOf(fiscalResponsibilities),
                municipalityCode, address, phone, email);
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
