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

/**
 * Fila de {@code credit_notes}. Detalle de persistencia, distinto del agregado: solo la conoce
 * {@link CreditNotePersistenceMapper}. La factura se referencia por id, no como asociación JPA:
 * son agregados separados (ADR 0009).
 */
@Entity
@Table(name = "credit_notes")
class CreditNoteEntity {

    @Id
    UUID id;

    @Version
    Long version;

    @Column(name = "reference_code", nullable = false, updatable = false, length = 50)
    String referenceCode;

    @Column(name = "invoice_id", nullable = false, updatable = false)
    UUID invoiceId;

    @Column(nullable = false, updatable = false, length = 20)
    String reason;

    @Column(nullable = false, length = 20)
    String status;

    @Column(name = "issue_date", nullable = false, updatable = false)
    LocalDate issueDate;

    @Column(updatable = false, length = 250)
    String observation;

    @Column(name = "edoc_number")
    String edocNumber;

    @Column(name = "edoc_unique_code", length = 96)
    String edocUniqueCode;

    @Column(name = "edoc_qr_url")
    String edocQrUrl;

    @Column(name = "edoc_public_url")
    String edocPublicUrl;

    @Column(name = "edoc_validated_at")
    Instant edocValidatedAt;

    @Column(name = "edoc_official_total", precision = 18, scale = 2)
    BigDecimal edocOfficialTotal;

    @Column(name = "rejection_reason")
    String rejectionReason;

    /** Huella de la petición que creó la nota (idempotencia, ADR 0009). Nunca cambia. */
    @Column(name = "request_hash", nullable = false, updatable = false)
    String requestHash;

    /** Lo pone la base al insertar (DEFAULT now()); solo sirve para listar en orden de creación. */
    @Column(name = "created_at", insertable = false, updatable = false)
    Instant createdAt;

    /** Lista indexada por {@code sort_order}, como las líneas de la factura (ADR 0005). */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "credit_note_id", nullable = false, updatable = false)
    @OrderColumn(name = "sort_order", nullable = false)
    List<CreditNoteLineEntity> lines = new ArrayList<>();

    protected CreditNoteEntity() {
    }
}
