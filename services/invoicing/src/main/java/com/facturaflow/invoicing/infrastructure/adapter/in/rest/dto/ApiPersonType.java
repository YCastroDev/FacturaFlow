package com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto;

import com.facturaflow.invoicing.domain.model.PersonType;

/** Tipo de persona en el contrato de la API (ver {@link ApiDocumentType} sobre por qué es un enum propio). */
public enum ApiPersonType {
    NATURAL_PERSON,
    LEGAL_ENTITY;

    public PersonType toDomain() {
        return switch (this) {
            case NATURAL_PERSON -> PersonType.NATURAL_PERSON;
            case LEGAL_ENTITY -> PersonType.LEGAL_ENTITY;
        };
    }

    public static ApiPersonType from(PersonType type) {
        return switch (type) {
            case NATURAL_PERSON -> NATURAL_PERSON;
            case LEGAL_ENTITY -> LEGAL_ENTITY;
        };
    }
}
