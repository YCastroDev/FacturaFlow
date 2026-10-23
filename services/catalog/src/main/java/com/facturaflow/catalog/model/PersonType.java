package com.facturaflow.catalog.model;

/**
 * Tipo de persona del cliente. Una persona jurídica se identifica por su razón social y una natural por
 * sus nombres; no se deduce del tipo de documento (una persona natural comerciante también tiene NIT).
 */
public enum PersonType {
    NATURAL_PERSON,
    LEGAL_ENTITY
}
