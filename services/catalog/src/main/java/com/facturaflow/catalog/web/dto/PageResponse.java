package com.facturaflow.catalog.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Una página del listado. Formato propio (no el {@code Page} de Spring): es parte del contrato de la API. */
@Schema(description = "Página de resultados")
public record PageResponse<T>(
        List<T> items,
        @Schema(description = "Número de página, desde 0", example = "0")
        int page,
        @Schema(example = "20")
        int size,
        @Schema(description = "Total de registros que cumplen el filtro", example = "42")
        long totalItems,
        @Schema(example = "3")
        int totalPages) {
}
