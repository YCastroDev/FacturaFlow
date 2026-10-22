package com.facturaflow.invoicing.infrastructure.adapter.out.persistence;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Fila de {@code invoices}. Es un detalle de persistencia, distinto del agregado de dominio:
 * solo la conoce {@link InvoicePersistenceMapper}. Los enums se guardan como texto.
 */
@Entity
@Table(name = "invoices")
class InvoiceEntity {

    @Id
    UUID id;

    @Version
    Long version;

    @Column(name = "reference_code", nullable = false, updatable = false, length = 50)
    String referenceCode;

    @Column(nullable = false, length = 20)
    String status;

    @Column(name = "issue_date", nullable = false, updatable = false)
    LocalDate issueDate;

    @Column(name = "payment_method", nullable = false, updatable = false, length = 30)
    String paymentMethod;

    @Column(name = "payment_type", nullable = false, updatable = false, length = 10)
    String paymentType;

    @Column(name = "due_date", updatable = false)
    LocalDate dueDate;

    @Column(updatable = false, length = 250)
    String observation;

    @Column(name = "customer_document_type", nullable = false, updatable = false, length = 40)
    String customerDocumentType;

    @Column(name = "customer_document_number", nullable = false, updatable = false, length = 20)
    String customerDocumentNumber;

    @Column(name = "customer_check_digit", updatable = false)
    Short customerCheckDigit;

    @Column(name = "customer_person_type", nullable = false, updatable = false, length = 20)
    String customerPersonType;

    @Column(name = "customer_company_name", updatable = false)
    String customerCompanyName;

    @Column(name = "customer_names", updatable = false)
    String customerNames;

    @Column(name = "customer_email", updatable = false, length = 254)
    String customerEmail;

    @Column(name = "customer_tax_scheme", nullable = false, updatable = false, length = 20)
    String customerTaxScheme;

    /** Arreglo nativo de PostgreSQL ({@code TEXT[]}): un valor pequeño del snapshot, sin tabla propia (ADR 0007). */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "customer_fiscal_responsibilities", nullable = false, updatable = false, columnDefinition = "text[]")
    String[] customerFiscalResponsibilities;

    @Column(name = "customer_municipality_code", updatable = false, length = 5)
    String customerMunicipalityCode;

    @Column(name = "customer_address", updatable = false, length = 250)
    String customerAddress;

    @Column(name = "customer_phone", updatable = false, length = 16)
    String customerPhone;

    @Column(name = "edoc_number")
    String edocNumber;

    @Column(name = "edoc_cufe", length = 96)
    String edocCufe;

    @Column(name = "edoc_pdf_url")
    String edocPdfUrl;

    @Column(name = "edoc_validated_at")
    Instant edocValidatedAt;

    @Column(name = "edoc_official_total", precision = 18, scale = 2)
    BigDecimal edocOfficialTotal;

    @Column(name = "rejection_reason")
    String rejectionReason;

    /** Huella de la petición que creó la factura (idempotencia, ADR 0006). Nunca cambia. */
    @Column(name = "request_hash", nullable = false, updatable = false)
    String requestHash;

    /**
     * Lista indexada por {@code line_number} (no un "bag"): conserva el orden y permite
     * traer líneas e impuestos en una sola consulta sin MultipleBagFetchException (ADR 0005).
     */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "invoice_id", nullable = false, updatable = false)
    @OrderColumn(name = "line_number", nullable = false)
    List<InvoiceLineEntity> lines = new ArrayList<>();

    protected InvoiceEntity() {
    }
}
