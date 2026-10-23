package com.facturaflow.catalog.web.dto;

import static com.facturaflow.catalog.web.dto.RequestLimits.MAX_TAXES;
import static com.facturaflow.catalog.web.dto.RequestLimits.MAX_TEXT;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * Cuerpo de {@code POST /api/v1/products} y de {@code PUT /api/v1/products/{id}} (reemplazo completo).
 * Bean Validation solo revisa la FORMA; las reglas las valida el modelo (422).
 */
@Schema(description = "Datos de un producto. En PUT se envían TODOS (reemplazo completo) y el código debe ser el "
        + "mismo: no se puede cambiar.")
public record ProductRequest(
        @Schema(description = "Código único (sin distinguir mayúsculas), máximo 50 caracteres. No cambia nunca.",
                example = "CAFE-500")
        @NotBlank(message = "es obligatorio") @Size(max = MAX_TEXT, message = "es demasiado largo")
        String code,

        @Schema(description = "Máximo 300 caracteres", example = "Café molido 500 g")
        @NotBlank(message = "es obligatorio") @Size(max = MAX_TEXT, message = "es demasiado largo")
        String name,

        @Schema(description = "Precio unitario NETO en COP: sin impuestos ni descuentos. Mayor que 0 y máximo 2 "
                + "decimales (con más decimales responde 422: no se redondea).", example = "18500.00")
        @NotNull(message = "es obligatorio")
        BigDecimal unitPrice,

        @NotNull(message = "es obligatorio")
        ApiUnitOfMeasure unit,

        @Schema(description = "Excluido de IVA (no admite impuesto IVA). Opcional: false por defecto.")
        Boolean ivaExcluded,

        @Schema(description = "Obligatorio aunque esté vacío ([]): así un producto sin IVA nunca es un olvido. "
                + "Sin tipos repetidos.")
        @NotNull(message = "es obligatorio (use [] si no aplica ningún impuesto)")
        @Size(max = MAX_TAXES, message = "admite máximo " + MAX_TAXES + " impuestos")
        List<@NotNull(message = "no puede ser nulo") @Valid Tax> taxes) {

    public record Tax(
            @NotNull(message = "es obligatorio")
            ApiTaxType type,

            @Schema(description = "Tarifa en porcentaje (19 = 19 %), de 0 a 100, máximo 2 decimales", example = "19")
            @NotNull(message = "es obligatorio")
            BigDecimal rate) {
    }
}
