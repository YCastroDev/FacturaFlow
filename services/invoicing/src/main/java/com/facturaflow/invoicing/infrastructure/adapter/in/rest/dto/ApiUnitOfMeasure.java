package com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto;

import com.facturaflow.invoicing.domain.model.UnitOfMeasure;

/**
 * Unidad de medida en el contrato de la API (ver {@link ApiDocumentType} sobre por qué es un enum propio).
 * Solo las que el proveedor acepta (ADR 0007); servicios y horas se facturan en {@code UNIT}.
 */
public enum ApiUnitOfMeasure {
    UNIT,
    KILOGRAM,
    GRAM,
    POUND,
    LITER,
    MILLILITER,
    GALLON,
    METER;

    public UnitOfMeasure toDomain() {
        return switch (this) {
            case UNIT -> UnitOfMeasure.UNIT;
            case KILOGRAM -> UnitOfMeasure.KILOGRAM;
            case GRAM -> UnitOfMeasure.GRAM;
            case POUND -> UnitOfMeasure.POUND;
            case LITER -> UnitOfMeasure.LITER;
            case MILLILITER -> UnitOfMeasure.MILLILITER;
            case GALLON -> UnitOfMeasure.GALLON;
            case METER -> UnitOfMeasure.METER;
        };
    }

    public static ApiUnitOfMeasure from(UnitOfMeasure unit) {
        return switch (unit) {
            case UNIT -> UNIT;
            case KILOGRAM -> KILOGRAM;
            case GRAM -> GRAM;
            case POUND -> POUND;
            case LITER -> LITER;
            case MILLILITER -> MILLILITER;
            case GALLON -> GALLON;
            case METER -> METER;
        };
    }
}
