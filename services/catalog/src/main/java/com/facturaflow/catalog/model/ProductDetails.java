package com.facturaflow.catalog.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Los datos editables de un producto, validados (todo menos el código, que es su identidad).
 *
 * <p>Reglas (las mismas que la línea de factura de invoicing, para que un producto del catálogo
 * siempre se pueda facturar): nombre obligatorio de hasta 300 caracteres; precio unitario NETO (sin
 * impuestos ni descuentos) mayor que 0 y con máximo 2 decimales (más decimales es un error, no se
 * redondea); impuestos sin tipos repetidos; {@code ivaExcluded} no admite IVA. Los impuestos quedan
 * ordenados por tipo, así dos listas con el mismo contenido son iguales.
 */
public record ProductDetails(String name, BigDecimal unitPrice, UnitOfMeasure unit, boolean ivaExcluded,
                             List<ProductTax> taxes) {

    public static final int MAX_NAME_LENGTH = 300;
    /** NUMERIC(15,2): hasta 13 dígitos enteros. */
    static final int MAX_PRICE_INTEGER_DIGITS = 13;
    private static final int PRICE_DECIMALS = 2;

    public ProductDetails {
        name = Texts.required(name, "el nombre del producto", "nombre", MAX_NAME_LENGTH);
        unitPrice = validatePrice(unitPrice);
        if (unit == null) {
            throw CatalogRuleException.missing("la unidad de medida");
        }
        taxes = validateTaxes(taxes, ivaExcluded);
    }

    private static BigDecimal validatePrice(BigDecimal price) {
        if (price == null) {
            throw CatalogRuleException.missing("el precio unitario");
        }
        if (price.signum() <= 0) {
            throw new CatalogRuleException("El precio unitario debe ser mayor que cero (recibido: "
                    + price.toPlainString() + ").");
        }
        if (price.stripTrailingZeros().scale() > PRICE_DECIMALS) {
            throw new CatalogRuleException("El precio unitario admite máximo " + PRICE_DECIMALS + " decimales "
                    + "(recibido: " + price.toPlainString() + "); no se redondea un dato maestro.");
        }
        BigDecimal normalized = price.setScale(PRICE_DECIMALS, RoundingMode.UNNECESSARY);
        if (normalized.precision() - normalized.scale() > MAX_PRICE_INTEGER_DIGITS) {
            throw new CatalogRuleException("El precio unitario admite máximo " + MAX_PRICE_INTEGER_DIGITS
                    + " dígitos enteros.");
        }
        return normalized;
    }

    private static List<ProductTax> validateTaxes(List<ProductTax> taxes, boolean ivaExcluded) {
        if (taxes == null) {
            throw CatalogRuleException.missing("la lista de impuestos (use [] si no aplica ninguno)");
        }
        Set<TaxType> seen = EnumSet.noneOf(TaxType.class);
        for (ProductTax tax : taxes) {
            if (tax == null) {
                throw CatalogRuleException.missing("cada impuesto (la lista no admite valores nulos)");
            }
            if (!seen.add(tax.type())) {
                throw new CatalogRuleException("El impuesto " + tax.type() + " está repetido.");
            }
        }
        if (ivaExcluded && seen.contains(TaxType.IVA)) {
            throw new CatalogRuleException("Un producto excluido de IVA no puede tener impuesto IVA.");
        }
        return taxes.stream().sorted(Comparator.comparing(ProductTax::type)).toList();
    }
}
