package com.facturaflow.catalog.model;

/**
 * Unidades de medida de los productos: solo las 8 que acepta el proveedor de facturación (spike 0001,
 * ADR 0007). Copia deliberada de la de invoicing: agregar una exige que invoicing la acepte primero.
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
