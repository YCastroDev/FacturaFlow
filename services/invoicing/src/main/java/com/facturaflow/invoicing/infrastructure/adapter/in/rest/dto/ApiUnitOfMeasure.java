package com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto;

import com.facturaflow.invoicing.domain.model.UnitOfMeasure;

/** Unidad de medida en el contrato de la API (ver {@link ApiDocumentType} sobre por qué es un enum propio). */
public enum ApiUnitOfMeasure {
    UNIT,
    DOZEN,
    BOX,
    PACKAGE,
    KILOGRAM,
    GRAM,
    POUND,
    LITER,
    MILLILITER,
    GALLON,
    METER,
    CENTIMETER,
    SQUARE_METER,
    HOUR,
    DAY,
    SERVICE;

    public UnitOfMeasure toDomain() {
        return switch (this) {
            case UNIT -> UnitOfMeasure.UNIT;
            case DOZEN -> UnitOfMeasure.DOZEN;
            case BOX -> UnitOfMeasure.BOX;
            case PACKAGE -> UnitOfMeasure.PACKAGE;
            case KILOGRAM -> UnitOfMeasure.KILOGRAM;
            case GRAM -> UnitOfMeasure.GRAM;
            case POUND -> UnitOfMeasure.POUND;
            case LITER -> UnitOfMeasure.LITER;
            case MILLILITER -> UnitOfMeasure.MILLILITER;
            case GALLON -> UnitOfMeasure.GALLON;
            case METER -> UnitOfMeasure.METER;
            case CENTIMETER -> UnitOfMeasure.CENTIMETER;
            case SQUARE_METER -> UnitOfMeasure.SQUARE_METER;
            case HOUR -> UnitOfMeasure.HOUR;
            case DAY -> UnitOfMeasure.DAY;
            case SERVICE -> UnitOfMeasure.SERVICE;
        };
    }

    public static ApiUnitOfMeasure from(UnitOfMeasure unit) {
        return switch (unit) {
            case UNIT -> UNIT;
            case DOZEN -> DOZEN;
            case BOX -> BOX;
            case PACKAGE -> PACKAGE;
            case KILOGRAM -> KILOGRAM;
            case GRAM -> GRAM;
            case POUND -> POUND;
            case LITER -> LITER;
            case MILLILITER -> MILLILITER;
            case GALLON -> GALLON;
            case METER -> METER;
            case CENTIMETER -> CENTIMETER;
            case SQUARE_METER -> SQUARE_METER;
            case HOUR -> HOUR;
            case DAY -> DAY;
            case SERVICE -> SERVICE;
        };
    }
}
