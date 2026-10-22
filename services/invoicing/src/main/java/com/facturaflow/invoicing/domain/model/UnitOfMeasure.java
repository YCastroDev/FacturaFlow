package com.facturaflow.invoicing.domain.model;

/**
 * Unidades de medida propias del dominio; el adaptador las traduce al catálogo del proveedor.
 *
 * <p>Solo las unidades que el proveedor de facturación acepta hoy (ADR 0007, spike 0001): el
 * dominio no ofrece nada que después no se pueda emitir. Los servicios y las horas se facturan
 * en {@link #UNIT}, que la DIAN admite para servicios. Agregar una unidad exige que el proveedor
 * la acepte y una migración (CHECK).
 */
public enum UnitOfMeasure {
    UNIT,
    KILOGRAM,
    GRAM,
    POUND,
    LITER,
    MILLILITER,
    GALLON,
    METER
}
