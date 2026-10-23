package com.facturaflow.catalog.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Cliente del catálogo")
public record CustomerResponse(
        UUID id,
        ApiDocumentType documentType,
        String documentNumber,
        Integer checkDigit,
        ApiPersonType personType,
        String companyName,
        String names,
        @Schema(description = "Razón social o nombres, según el tipo de persona")
        String displayName,
        ApiTaxScheme taxScheme,
        List<ApiFiscalResponsibility> fiscalResponsibilities,
        String municipalityCode,
        String address,
        @Schema(description = "Normalizado: solo dígitos y '+' inicial", example = "+576015551234")
        String phone,
        String email,
        @Schema(description = "false = desactivado (no se borra porque ventas y facturas lo referencian)")
        boolean active,
        @Schema(description = "Versión actual. El header ETag de la respuesta es este número entre comillas "
                + "(\"3\"); envíelo en If-Match al actualizar.", example = "0")
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
