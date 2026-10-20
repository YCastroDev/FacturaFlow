package com.facturaflow.invoicing.domain.model;

import com.facturaflow.invoicing.domain.exception.InvalidInvoiceLineException;

/**
 * Línea de factura. {@code unitPrice} NO incluye impuestos.
 *
 * <p>Cálculo (redondeo por línea, ADR 0004):
 * <pre>
 *   bruto     = cantidad x precio unitario        (redondeado)
 *   descuento = bruto x %descuento                (redondeado)
 *   base      = bruto - descuento
 *   impuesto  = base x %impuesto, o 0 si excluida (redondeado)
 *   total     = base + impuesto
 * </pre>
 * El descuento se aplica antes del impuesto: el IVA se calcula sobre el valor descontado.
 */
public record InvoiceLine(String code, String name, Quantity quantity, Money unitPrice,
                          DiscountRate discountRate, TaxRate taxRate, UnitOfMeasure unit, boolean excluded) {

    public static final int MAX_CODE_LENGTH = 50;
    public static final int MAX_NAME_LENGTH = 300;

    public InvoiceLine {
        code = requireText(code, "un código", "código", MAX_CODE_LENGTH);
        name = requireText(name, "un nombre", "nombre", MAX_NAME_LENGTH);
        if (quantity == null) {
            throw InvalidInvoiceLineException.missing("una cantidad");
        }
        if (unitPrice == null) {
            throw InvalidInvoiceLineException.missing("un precio unitario");
        }
        if (unitPrice.isZero()) {
            throw InvalidInvoiceLineException.unitPriceNotPositive(code, unitPrice.amount());
        }
        if (discountRate == null) {
            throw InvalidInvoiceLineException.missing("un porcentaje de descuento (use DiscountRate.NONE)");
        }
        if (taxRate == null) {
            throw InvalidInvoiceLineException.missing("una tarifa de impuesto (use TaxRate.ZERO)");
        }
        if (unit == null) {
            throw InvalidInvoiceLineException.missing("una unidad de medida");
        }
        if (excluded && !taxRate.isZero()) {
            throw InvalidInvoiceLineException.excludedWithTax(code, taxRate.percent());
        }
    }

    public Money grossAmount() {
        return Money.rounded(quantity.value().multiply(unitPrice.amount()));
    }

    public Money discountAmount() {
        return grossAmount().percentage(discountRate.percent());
    }

    /** Base gravable: valor bruto menos descuento. */
    public Money taxableAmount() {
        return grossAmount().minus(discountAmount());
    }

    public Money taxAmount() {
        return excluded ? Money.ZERO : taxableAmount().percentage(taxRate.percent());
    }

    public Money total() {
        return taxableAmount().plus(taxAmount());
    }

    private static String requireText(String value, String missingDescription, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw InvalidInvoiceLineException.missing(missingDescription);
        }
        String stripped = value.strip();
        if (stripped.length() > maxLength) {
            throw InvalidInvoiceLineException.tooLong(field, maxLength);
        }
        return stripped;
    }
}
