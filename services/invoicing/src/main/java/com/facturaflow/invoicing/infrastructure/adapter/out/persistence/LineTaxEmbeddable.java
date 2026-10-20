package com.facturaflow.invoicing.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;

/** Fila de {@code invoice_line_taxes}: un valor sin identidad propia, como LineTax en el dominio. */
@Embeddable
class LineTaxEmbeddable {

    @Column(name = "tax_type", nullable = false, length = 10)
    String taxType;

    @Column(nullable = false, precision = 5, scale = 2)
    BigDecimal rate;

    protected LineTaxEmbeddable() {
    }

    LineTaxEmbeddable(String taxType, BigDecimal rate) {
        this.taxType = taxType;
        this.rate = rate;
    }
}
