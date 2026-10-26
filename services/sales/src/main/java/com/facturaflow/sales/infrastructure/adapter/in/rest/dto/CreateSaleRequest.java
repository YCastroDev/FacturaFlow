package com.facturaflow.sales.infrastructure.adapter.in.rest.dto;

import static com.facturaflow.sales.infrastructure.adapter.in.rest.dto.RequestLimits.MAX_LINES;
import static com.facturaflow.sales.infrastructure.adapter.in.rest.dto.RequestLimits.MAX_TEXT;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Cuerpo de {@code POST /api/v1/sales}. Solo lo que decide el vendedor: precios, nombres, impuestos y datos
 * fiscales salen SIEMPRE de catalog (ADR 0012). Bean Validation solo revisa la FORMA; las reglas las
 * valida el dominio (422).
 */
@Schema(description = "Petición para confirmar una venta")
public record CreateSaleRequest(
        @Schema(description = "Código único de la venta: clave de idempotencia (y, después, el de su factura). "
                + "Letras, números, '-' y '_', máximo 50.", example = "VENTA-2026-0001")
        @NotBlank(message = "es obligatorio") @Size(max = MAX_TEXT, message = "es demasiado largo")
        String referenceCode,

        @NotNull(message = "es obligatorio") @Valid
        Customer customer,

        @Schema(description = "De 1 a 100 líneas. Un producto puede repetirse (p. ej. con otro descuento).")
        @NotNull(message = "es obligatorio") @Size(max = MAX_LINES, message = "es demasiado larga")
        List<@NotNull(message = "no puede ser nulo") @Valid Line> lines,

        @NotNull(message = "es obligatorio")
        ApiPaymentMethod paymentMethod,

        @NotNull(message = "es obligatorio") @Valid
        PaymentTerms paymentTerms,

        @Schema(description = "Opcional, máximo 250 caracteres", example = "Entregar en portería")
        @Size(max = MAX_TEXT, message = "es demasiado larga")
        String observation) {

    @Schema(description = "El cliente, por su documento (sin DV): sus datos salen de catalog")
    public record Customer(
            @NotNull(message = "es obligatorio")
            ApiDocumentType documentType,

            @Schema(example = "890903938")
            @NotBlank(message = "es obligatorio") @Size(max = MAX_TEXT, message = "es demasiado largo")
            String documentNumber) {
    }

    @Schema(description = "Un producto, por su código: nombre, precio neto, unidad e impuestos salen de catalog")
    public record Line(
            @Schema(description = "Código del producto en catalog (sin distinguir mayúsculas)", example = "CAFE-500")
            @NotBlank(message = "es obligatorio") @Size(max = MAX_TEXT, message = "es demasiado largo")
            String productCode,

            @Schema(description = "Mayor que cero, máximo 2 decimales", example = "2")
            @NotNull(message = "es obligatorio")
            BigDecimal quantity,

            @Schema(description = "Porcentaje de descuento (0 a 100), antes de impuestos. Opcional: 0.", example = "10")
            BigDecimal discountRate) {
    }

    public record PaymentTerms(
            @NotNull(message = "es obligatorio")
            ApiPaymentTermsType type,

            @Schema(description = "Solo para CREDIT: posterior a la fecha de la venta", example = "2030-01-31")
            LocalDate dueDate) {
    }
}
