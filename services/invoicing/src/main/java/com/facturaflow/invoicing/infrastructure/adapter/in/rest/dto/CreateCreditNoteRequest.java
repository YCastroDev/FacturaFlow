package com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto;

import static com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto.RequestLimits.MAX_LINES;
import static com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto.RequestLimits.MAX_TEXT;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * Cuerpo de {@code POST /api/v1/invoices/{id}/credit-notes}. Bean Validation solo revisa la FORMA;
 * las reglas (cantidades, saldo, líneas según el motivo) las valida el dominio y responden 422.
 * No lleva cliente, precios ni impuestos: salen de la factura guardada (ADR 0009).
 */
@Schema(description = "Petición para crear una nota crédito PENDING sobre una factura ISSUED")
public record CreateCreditNoteRequest(
        @Schema(description = "Código único de la devolución; es la clave de idempotencia. Letras, números, '-' y "
                + "'_', máximo 50.", example = "DEV-2026-0001")
        @NotBlank(message = "es obligatorio") @Size(max = MAX_TEXT, message = "es demasiado largo")
        String referenceCode,

        @Schema(description = "PARTIAL_RETURN acredita las líneas y cantidades indicadas; TOTAL_ANNULMENT acredita "
                + "todo lo que queda por acreditar y no lleva líneas")
        @NotNull(message = "es obligatorio")
        ApiCreditNoteReason reason,

        @Schema(description = "Solo en PARTIAL_RETURN (al menos una). Omitir o [] en TOTAL_ANNULMENT.")
        @Size(max = MAX_LINES, message = "admite máximo " + MAX_LINES + " líneas")
        List<@NotNull(message = "no puede ser nulo") @Valid Line> lines,

        @Schema(description = "Opcional, máximo 250 caracteres. Con el proveedor falso: si contiene SIMULAR_RECHAZO "
                + "la emisión se rechaza; si contiene SIMULAR_CAIDA la emisión responde 503 y la nota sigue PENDING.",
                example = "Cliente devolvió una unidad")
        @Size(max = MAX_TEXT, message = "es demasiado largo")
        String observation) {

    @Schema(description = "Línea de la factura a acreditar")
    public record Line(
            @Schema(description = "Número de la línea en la factura (lineNumber, desde 1)", example = "1")
            @NotNull(message = "es obligatorio")
            Integer lineNumber,

            @Schema(description = "Cantidad a acreditar: mayor que cero, máximo 2 decimales y sin superar lo que "
                    + "queda por acreditar de esa línea", example = "1")
            @NotNull(message = "es obligatorio")
            BigDecimal quantity) {
    }
}
