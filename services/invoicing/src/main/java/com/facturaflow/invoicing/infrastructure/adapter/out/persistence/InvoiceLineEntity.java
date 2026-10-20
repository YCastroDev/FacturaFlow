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

/** Fila de {@code invoice_lines}. Las líneas no cambian después de crearse (ADR 0005). */
@Entity
@Table(name = "invoice_lines")
class InvoiceLineEntity {

    @Id
    UUID id;

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

    /** Lista indexada por {@code sort_order}: conserva el orden de los impuestos del dominio. */
    @ElementCollection
    @CollectionTable(name = "invoice_line_taxes", joinColumns = @JoinColumn(name = "line_id"))
    @OrderColumn(name = "sort_order", nullable = false)
    List<LineTaxEmbeddable> taxes = new ArrayList<>();

    protected InvoiceLineEntity() {
    }
}
