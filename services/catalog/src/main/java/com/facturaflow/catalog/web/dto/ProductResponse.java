package com.facturaflow.catalog.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Producto del catálogo")
public record ProductResponse(
        UUID id,
        String code,
        String name,
        @Schema(description = "Precio unitario neto (sin impuestos ni descuentos)", example = "18500.00")
        BigDecimal unitPrice,
        ApiUnitOfMeasure unit,
        boolean ivaExcluded,
        @Schema(description = "Ordenados por tipo")
        List<Tax> taxes,
        @Schema(description = "false = desactivado (no se borra porque ventas y facturas lo referencian)")
        boolean active,
        @Schema(description = "Versión actual. El header ETag de la respuesta es este número entre comillas "
                + "(\"3\"); envíelo en If-Match al actualizar.", example = "0")
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public record Tax(ApiTaxType type, BigDecimal rate) {
    }
}
