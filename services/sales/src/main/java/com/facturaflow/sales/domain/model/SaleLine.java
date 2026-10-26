package com.facturaflow.sales.domain.model;

import com.facturaflow.sales.domain.exception.InvalidSaleLineException;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Línea de venta: una copia del producto de catalog (id, código, nombre, precio neto, unidad, impuestos)
 * más la cantidad y el descuento de la petición. {@code unitPrice} NO incluye impuestos ni descuentos.
 *
 * <p>COPIA DELIBERADA del cálculo de {@code InvoiceLine} de invoicing (ADR 0012): el descuento se
 * aplica antes del impuesto, cada valor se redondea HALF_UP a 2 decimales y cada impuesto se redondea
 * por separado. {@code SaleLineTest} repite los casos de {@code InvoiceLineTest}: si el cálculo cambia
 * en un lado, cambia en el otro. Invoicing sigue siendo la autoridad final.
 *
 * <p>{@code taxes} no repite tipos y puede estar vacía. {@code ivaExcluded} significa "excluida de IVA":
 * no admite un impuesto de tipo IVA (ni siquiera con tarifa 0), pero sí otros, como el INC.
 */
public record SaleLine(UUID productId, String code, String name, Quantity quantity, Money unitPrice,
                       DiscountRate discountRate, List<LineTax> taxes, UnitOfMeasure unit, boolean ivaExcluded) {

    public static final int MAX_CODE_LENGTH = 50;
    public static final int MAX_NAME_LENGTH = 300;

    public SaleLine {
        if (productId == null) {
            throw InvalidSaleLineException.missing("el id del producto en catalog");
        }
        code = requireText(code, "un código", "código", MAX_CODE_LENGTH);
        name = requireText(name, "un nombre", "nombre", MAX_NAME_LENGTH);
        if (quantity == null) {
            throw InvalidSaleLineException.missing("una cantidad");
        }
        if (unitPrice == null) {
            throw InvalidSaleLineException.missing("un precio unitario");
        }
        if (unitPrice.isZero()) {
            throw InvalidSaleLineException.unitPriceNotPositive(code, unitPrice.amount());
        }
        if (discountRate == null) {
            throw InvalidSaleLineException.missing("un porcentaje de descuento (use DiscountRate.NONE)");
        }
        if (unit == null) {
            throw InvalidSaleLineException.missing("una unidad de medida");
        }
        taxes = validateTaxes(code, taxes, ivaExcluded);
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

    private static List<LineTax> validateTaxes(String code, List<LineTax> taxes, boolean ivaExcluded) {
        if (taxes == null) {
            throw InvalidSaleLineException.missing("la lista de impuestos (vacía si no aplica ninguno)");
        }
        if (taxes.stream().anyMatch(Objects::isNull)) {
            throw InvalidSaleLineException.missing("impuestos no nulos");
        }
        Set<TaxType> seen = EnumSet.noneOf(TaxType.class);
        for (LineTax tax : taxes) {
            if (!seen.add(tax.type())) {
                throw InvalidSaleLineException.duplicateTax(code, tax.type().name());
            }
        }
        if (ivaExcluded && seen.contains(TaxType.IVA)) {
            throw InvalidSaleLineException.excludedWithIva(code);
        }
        return List.copyOf(taxes);
    }

    private static String requireText(String value, String missingDescription, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw InvalidSaleLineException.missing(missingDescription);
        }
        String stripped = value.strip();
        if (stripped.length() > maxLength) {
            throw InvalidSaleLineException.tooLong(field, maxLength);
        }
        return stripped;
    }
}
