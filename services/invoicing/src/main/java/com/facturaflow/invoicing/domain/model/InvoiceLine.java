package com.facturaflow.invoicing.domain.model;

import com.facturaflow.invoicing.domain.exception.InvalidInvoiceLineException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Línea de factura. {@code unitPrice} NO incluye impuestos ni descuentos.
 *
 * <p>Cálculo (redondeo por línea, ADR 0004):
 * <pre>
 *   bruto     = cantidad x precio unitario        (redondeado)
 *   descuento = bruto x %descuento                (redondeado)
 *   base      = bruto - descuento
 *   impuesto  = Σ (base x %tarifa) de cada impuesto, cada uno redondeado por separado
 *   total     = base + impuesto
 * </pre>
 * El descuento se aplica antes del impuesto: los impuestos se calculan sobre el valor descontado.
 *
 * <p>{@code taxes} no repite tipos y puede estar vacía. {@code excluded} significa
 * "excluida de IVA": una línea excluida no puede tener un impuesto de tipo IVA
 * (sí otros, como INC).
 */
public record InvoiceLine(String code, String name, Quantity quantity, Money unitPrice,
                          DiscountRate discountRate, List<LineTax> taxes, UnitOfMeasure unit, boolean excluded) {

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
        if (unit == null) {
            throw InvalidInvoiceLineException.missing("una unidad de medida");
        }
        taxes = validateTaxes(code, taxes, excluded);
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

    /** Suma de todos los impuestos de la línea, cada uno redondeado por separado. */
    public Money taxAmount() {
        Money base = taxableAmount();
        return taxes.stream().map(tax -> tax.amountOn(base)).reduce(Money.ZERO, Money::plus);
    }

    /** Valor del impuesto de un tipo; cero si la línea no lo tiene. */
    public Money taxAmount(TaxType type) {
        Money base = taxableAmount();
        return taxes.stream().filter(tax -> tax.type() == type).findFirst()
                .map(tax -> tax.amountOn(base)).orElse(Money.ZERO);
    }

    public Money total() {
        return taxableAmount().plus(taxAmount());
    }

    private static List<LineTax> validateTaxes(String code, List<LineTax> taxes, boolean excluded) {
        if (taxes == null) {
            throw InvalidInvoiceLineException.missing("una lista de impuestos (vacía si no aplica ninguno)");
        }
        if (taxes.stream().anyMatch(Objects::isNull)) {
            throw InvalidInvoiceLineException.missing("impuestos no nulos");
        }
        Set<TaxType> seen = new HashSet<>();
        for (LineTax tax : taxes) {
            if (!seen.add(tax.type())) {
                throw InvalidInvoiceLineException.duplicateTax(code, tax.type().name());
            }
        }
        if (excluded && seen.contains(TaxType.IVA)) {
            throw InvalidInvoiceLineException.excludedWithIva(code);
        }
        return List.copyOf(taxes);
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
