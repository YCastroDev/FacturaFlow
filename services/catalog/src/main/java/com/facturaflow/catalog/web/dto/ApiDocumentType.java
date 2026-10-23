package com.facturaflow.catalog.web.dto;

/** Tipo de documento en la API (mismos nombres que la API de invoicing). NIT exige checkDigit. */
public enum ApiDocumentType {
    CITIZENSHIP_CARD,
    NIT,
    FOREIGNER_ID_CARD,
    PASSPORT,
    IDENTITY_CARD,
    CIVIL_REGISTRY,
    FOREIGNER_CARD,
    FOREIGN_ID_DOCUMENT,
    SPECIAL_STAY_PERMIT,
    TEMPORARY_PROTECTION_PERMIT,
    FOREIGN_NIT
}
