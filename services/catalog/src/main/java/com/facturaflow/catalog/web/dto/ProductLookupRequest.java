package com.facturaflow.catalog.web.dto;

import static com.facturaflow.catalog.web.dto.RequestLimits.MAX_LOOKUP_CODES;
import static com.facturaflow.catalog.web.dto.RequestLimits.MAX_TEXT;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Cuerpo de {@code POST /api/v1/products/lookup}: consulta por lote (la usa sales al crear una venta). */
@Schema(description = "Códigos a consultar, de 1 a " + MAX_LOOKUP_CODES + " (sin distinguir mayúsculas; los repetidos "
        + "cuentan una vez)")
public record ProductLookupRequest(
        @Schema(example = "[\"CAFE-500\", \"ARROZ-KG\"]")
        @NotNull(message = "es obligatorio")
        @Size(min = 1, max = MAX_LOOKUP_CODES, message = "admite de 1 a " + MAX_LOOKUP_CODES + " códigos")
        List<@NotBlank(message = "no puede estar vacío") @Size(max = MAX_TEXT, message = "es demasiado largo") String>
                codes) {
}
