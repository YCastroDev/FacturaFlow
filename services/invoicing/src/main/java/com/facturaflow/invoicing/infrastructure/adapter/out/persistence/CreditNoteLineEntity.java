package com.facturaflow.invoicing.infrastructure.adapter.out.persistence;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Fila de {@code credit_note_lines}: el número de la línea de la factura y la copia de esa línea
 * con la cantidad acreditada (ADR 0009). No cambia después de crearse.
 */
@Entity
@Table(name = "credit_note_lines")
class CreditNoteLineEntity {

    @Id
    UUID id;

    @Column(name = "invoice_line_number", nullable = false, updatable = false)
    int invoiceLineNumber;

    @Column(nullable = false, updatable = false, length = 50)
    String code;

    @Column(nullable = false, updatable = false, length = 300)
    String name;

    @Column(nullable = false, updatable = false, precision = 14, scale = 2)
    BigDecimal quantity;

    @Column(name = "unit_price", nullable = false, updatable = false, precision = 18, scale = 2)
    BigDecimal unitPrice;

    @Column(name = "discount_rate", nullable = false, updatable = false, precision = 5, scale = 2)
    BigDecimal discountRate;

    @Column(name = "unit_of_measure", nullable = false, updatable = false, length = 20)
    String unitOfMeasure;

    @Column(name = "iva_excluded", nullable = false, updatable = false)
    boolean ivaExcluded;

    @ElementCollection
    @CollectionTable(name = "credit_note_line_taxes", joinColumns = @JoinColumn(name = "line_id"))
    @OrderColumn(name = "sort_order", nullable = false)
    List<LineTaxEmbeddable> taxes = new ArrayList<>();

    protected CreditNoteLineEntity() {
    }
}
