package com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto;

import com.facturaflow.invoicing.domain.model.DocumentType;

/**
 * Tipo de documento en el contrato de la API. Es un enum propio (no el del dominio) para que
 * renombrar algo en el dominio no rompa a los clientes; los {@code switch} exhaustivos hacen
 * que un valor nuevo en cualquiera de los dos lados no compile hasta mapearlo.
 */
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
    FOREIGN_NIT;

    public DocumentType toDomain() {
        return switch (this) {
            case CITIZENSHIP_CARD -> DocumentType.CITIZENSHIP_CARD;
            case NIT -> DocumentType.NIT;
            case FOREIGNER_ID_CARD -> DocumentType.FOREIGNER_ID_CARD;
            case PASSPORT -> DocumentType.PASSPORT;
            case IDENTITY_CARD -> DocumentType.IDENTITY_CARD;
            case CIVIL_REGISTRY -> DocumentType.CIVIL_REGISTRY;
            case FOREIGNER_CARD -> DocumentType.FOREIGNER_CARD;
            case FOREIGN_ID_DOCUMENT -> DocumentType.FOREIGN_ID_DOCUMENT;
            case SPECIAL_STAY_PERMIT -> DocumentType.SPECIAL_STAY_PERMIT;
            case TEMPORARY_PROTECTION_PERMIT -> DocumentType.TEMPORARY_PROTECTION_PERMIT;
            case FOREIGN_NIT -> DocumentType.FOREIGN_NIT;
        };
    }

    public static ApiDocumentType from(DocumentType type) {
        return switch (type) {
            case CITIZENSHIP_CARD -> CITIZENSHIP_CARD;
            case NIT -> NIT;
            case FOREIGNER_ID_CARD -> FOREIGNER_ID_CARD;
            case PASSPORT -> PASSPORT;
            case IDENTITY_CARD -> IDENTITY_CARD;
            case CIVIL_REGISTRY -> CIVIL_REGISTRY;
            case FOREIGNER_CARD -> FOREIGNER_CARD;
            case FOREIGN_ID_DOCUMENT -> FOREIGN_ID_DOCUMENT;
            case SPECIAL_STAY_PERMIT -> SPECIAL_STAY_PERMIT;
            case TEMPORARY_PROTECTION_PERMIT -> TEMPORARY_PROTECTION_PERMIT;
            case FOREIGN_NIT -> FOREIGN_NIT;
        };
    }
}
