package com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto;

import static com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto.RequestLimits.MAX_LINES;
import static com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto.RequestLimits.MAX_TAXES_PER_LINE;
import static com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto.RequestLimits.MAX_TEXT;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Cuerpo de {@code POST /api/v1/invoices}. Bean Validation solo revisa la FORMA (presencia,
 * tipos y topes anti-abuso); las reglas de negocio las valida el dominio (ADR 0006, D6).
 */
@Schema(description = "Petición para crear una factura PENDING")
public record CreateInvoiceRequest(
        @Schema(description = "Código único de la venta; es la clave de idempotencia. Letras, números, '-' y '_', "
                + "máximo 50.", example = "VENTA-2026-0001")
        @NotBlank(message = "es obligatorio") @Size(max = MAX_TEXT, message = "es demasiado largo")
        String referenceCode,

        @NotNull(message = "es obligatorio") @Valid
        Customer customer,

        @Schema(description = "Al menos una línea")
        @NotNull(message = "es obligatorio") @Size(max = MAX_LINES, message = "admite máximo " + MAX_LINES + " líneas")
        List<@NotNull(message = "no puede ser nulo") @Valid Line> lines,

        @NotNull(message = "es obligatorio")
        ApiPaymentMethod paymentMethod,

        @NotNull(message = "es obligatorio") @Valid
        PaymentTerms paymentTerms,

        @Schema(description = "Opcional, máximo 250 caracteres. Con el proveedor falso: si contiene SIMULAR_RECHAZO "
                + "la emisión se rechaza; si contiene SIMULAR_CAIDA la emisión responde 503 y la factura sigue "
                + "PENDING.", example = "Entregar en portería")
        @Size(max = MAX_TEXT, message = "es demasiado largo")
        String observation) {

    @Schema(description = "Datos del cliente al momento de la venta")
    public record Customer(
            @NotNull(message = "es obligatorio")
            ApiDocumentType documentType,

            @Schema(example = "890903938")
            @NotBlank(message = "es obligatorio") @Size(max = MAX_TEXT, message = "es demasiado largo")
            String documentNumber,

            @Schema(description = "Dígito de verificación: obligatorio para NIT y prohibido en los demás tipos",
                    example = "8")
            Integer checkDigit,

            @Schema(description = "Razón social (empresa). Se requiere companyName o names.")
            @Size(max = MAX_TEXT, message = "es demasiado largo")
            String companyName,

            @Schema(description = "Nombres (persona natural). Se requiere companyName o names.")
            @Size(max = MAX_TEXT, message = "es demasiado largo")
            String names,

            @Size(max = MAX_TEXT, message = "es demasiado largo")
            String email) {
    }

    @Schema(description = "Línea de factura. unitPrice va SIN impuestos ni descuentos.")
    public record Line(
            @NotBlank(message = "es obligatorio") @Size(max = MAX_TEXT, message = "es demasiado largo")
            String code,

            @NotBlank(message = "es obligatorio") @Size(max = MAX_TEXT, message = "es demasiado largo")
            String name,

            @Schema(description = "Mayor que cero, máximo 2 decimales", example = "2")
            @NotNull(message = "es obligatorio")
            BigDecimal quantity,

            @Schema(description = "Precio unitario en COP, sin impuestos ni descuentos, máximo 2 decimales",
                    example = "85000.00")
            @NotNull(message = "es obligatorio")
            BigDecimal unitPrice,

            @Schema(description = "Porcentaje de descuento (0 a 100). Opcional: 0 por defecto.", example = "10")
            BigDecimal discountRate,

            @NotNull(message = "es obligatorio")
            ApiUnitOfMeasure unit,

            @Schema(description = "Excluida de IVA (no admite impuesto IVA). Opcional: false por defecto.")
            Boolean ivaExcluded,

            @Schema(description = "Obligatorio aunque esté vacío ([]): así una línea sin IVA nunca es un olvido.")
            @NotNull(message = "es obligatorio (use [] si no aplica ningún impuesto)")
            @Size(max = MAX_TAXES_PER_LINE, message = "admite máximo " + MAX_TAXES_PER_LINE + " impuestos")
            List<@NotNull(message = "no puede ser nulo") @Valid Tax> taxes) {
    }

    public record Tax(
            @NotNull(message = "es obligatorio")
            ApiTaxType type,

            @Schema(description = "Tarifa en porcentaje (19 = 19%)", example = "19")
            @NotNull(message = "es obligatorio")
            BigDecimal rate) {
    }

    @Schema(description = "Forma de pago. dueDate solo se admite (y se exige) con type CREDIT.")
    public record PaymentTerms(
            @NotNull(message = "es obligatorio")
            ApiPaymentTermsType type,

            @Schema(description = "Fecha de vencimiento (solo CREDIT); debe ser posterior a la fecha de emisión",
                    example = "2030-01-31")
            LocalDate dueDate) {

        /** Regla de forma: CASH no lleva fecha. Que CREDIT la tenga lo valida el dominio. */
        @JsonIgnore
        @Schema(hidden = true)
        @AssertTrue(message = "dueDate solo se admite con type CREDIT")
        public boolean isDueDateOnlyForCredit() {
            return type != ApiPaymentTermsType.CASH || dueDate == null;
        }
    }
}
