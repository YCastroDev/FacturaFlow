package com.facturaflow.catalog.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Un impuesto del producto: tipo y tarifa en porcentaje (19.00 = 19 %), de 0 a 100 con máximo 2
 * decimales (la misma regla que {@code TaxRate} de invoicing). Inmutable.
 */
@Embeddable
public final class ProductTax {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final int MAX_DECIMALS = 2;

    @Enumerated(EnumType.STRING)
    @Column(name = "tax_type", nullable = false)
    private TaxType type;

    @Column(name = "rate", nullable = false)
    private BigDecimal rate;

    protected ProductTax() {
        // JPA
    }

    public ProductTax(TaxType type, BigDecimal rate) {
        if (type == null) {
            throw CatalogRuleException.missing("el tipo de cada impuesto");
        }
        if (rate == null) {
            throw CatalogRuleException.missing("la tarifa de cada impuesto");
        }
        if (rate.signum() < 0 || rate.compareTo(ONE_HUNDRED) > 0 || rate.stripTrailingZeros().scale() > MAX_DECIMALS) {
            throw CatalogRuleException.percentage(type.name(), rate);
        }
        this.type = type;
        this.rate = rate.setScale(MAX_DECIMALS, RoundingMode.UNNECESSARY);
    }

    public static ProductTax of(TaxType type, String rate) {
        return new ProductTax(type, new BigDecimal(rate));
    }

    public TaxType type() {
        return type;
    }

    public BigDecimal rate() {
        return rate;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ProductTax tax && type == tax.type && rate.compareTo(tax.rate) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, rate.stripTrailingZeros());
    }

    @Override
    public String toString() {
        return type + " " + rate.toPlainString() + "%";
    }
}
