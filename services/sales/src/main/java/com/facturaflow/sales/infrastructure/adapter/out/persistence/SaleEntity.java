package com.facturaflow.sales.infrastructure.adapter.out.persistence;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Fila de {@code sales}. Detalle de persistencia, distinto del agregado: solo la conoce
 * {@link SalePersistenceMapper}. Los enums se guardan como texto. Todo es de solo inserción salvo el
 * estado, el resultado de la facturación (ADR 0015) y la versión.
 */
@Entity
@Table(name = "sales")
class SaleEntity {

    @Id
    UUID id;

    @Version
    Long version;

    @Column(name = "reference_code", nullable = false, updatable = false, length = 50)
    String referenceCode;

    @Column(name = "request_hash", nullable = false, updatable = false)
    String requestHash;

    @Column(nullable = false, length = 20)
    String status;

    @Column(name = "sale_date", nullable = false, updatable = false)
    LocalDate saleDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    Instant createdAt;

    @Column(name = "payment_method", nullable = false, updatable = false, length = 30)
    String paymentMethod;

    @Column(name = "payment_type", nullable = false, updatable = false, length = 10)
    String paymentType;

    @Column(name = "due_date", updatable = false)
    LocalDate dueDate;

    @Column(updatable = false, length = 250)
    String observation;

    /** Quién hizo la venta (V4): las dos o ninguna, NULL solo en las ventas creadas antes de registrarlo. */
    @Column(name = "sold_by_user_id", updatable = false, length = 255)
    String soldByUserId;

    @Column(name = "sold_by_username", updatable = false, length = 255)
    String soldByUsername;

    @Column(name = "customer_id", nullable = false, updatable = false)
    UUID customerId;

    @Column(name = "customer_document_type", nullable = false, updatable = false, length = 40)
    String customerDocumentType;

    @Column(name = "customer_document_number", nullable = false, updatable = false, length = 20)
    String customerDocumentNumber;

    @Column(name = "customer_check_digit", updatable = false)
    Integer customerCheckDigit;

    @Column(name = "customer_person_type", nullable = false, updatable = false, length = 20)
    String customerPersonType;

    @Column(name = "customer_company_name", updatable = false)
    String customerCompanyName;

    @Column(name = "customer_names", updatable = false)
    String customerNames;

    @Column(name = "customer_tax_scheme", nullable = false, updatable = false, length = 20)
    String customerTaxScheme;

    /** Arreglo nativo de PostgreSQL ({@code TEXT[]}), como en invoicing: un valor pequeño del snapshot. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "customer_fiscal_responsibilities", nullable = false, updatable = false, columnDefinition = "text[]")
    String[] customerFiscalResponsibilities;

    @Column(name = "customer_municipality_code", updatable = false, length = 5)
    String customerMunicipalityCode;

    @Column(name = "customer_address", updatable = false, length = 250)
    String customerAddress;

    @Column(name = "customer_phone", updatable = false, length = 16)
    String customerPhone;

    @Column(name = "customer_email", updatable = false, length = 254)
    String customerEmail;

    // ----- Resultado de la facturación (ADR 0015): una de las dos partes, según el estado -----

    @Column(name = "invoice_id")
    UUID invoiceId;

    @Column(name = "invoice_number")
    String invoiceNumber;

    @Column(name = "invoice_cufe")
    String invoiceCufe;

    @Column(name = "invoiced_at")
    Instant invoicedAt;

    @Column(name = "invoice_failure_reason", length = 1000)
    String invoiceFailureReason;

    @Column(name = "invoice_failed_at")
    Instant invoiceFailedAt;

    @Column(name = "rejected_invoice_id")
    UUID rejectedInvoiceId;

    /** Lista indexada por {@code line_index}: conserva el orden de las líneas de la venta. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "sale_id", nullable = false, updatable = false)
    @OrderColumn(name = "line_index", nullable = false)
    List<SaleLineEntity> lines = new ArrayList<>();

    protected SaleEntity() {
    }
}
