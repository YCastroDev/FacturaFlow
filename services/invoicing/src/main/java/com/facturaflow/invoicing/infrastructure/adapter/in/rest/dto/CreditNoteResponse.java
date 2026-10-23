package com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Representación de una nota crédito en la API. Los montos los calcula el dominio con las mismas
 * reglas que la factura; aquí solo se copian. Los campos sin valor se omiten.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Nota crédito con sus totales calculados")
public record CreditNoteResponse(
        UUID id,
        String referenceCode,
        @Schema(description = "Factura acreditada") UUID invoiceId,
        ApiCreditNoteReason reason,
        ApiCreditNoteStatus status,
        @Schema(description = "Fecha de la nota (fecha de Colombia)") LocalDate issueDate,
        String observation,
        List<Line> lines,
        InvoiceResponse.Totals totals,
        @Schema(description = "Solo si la nota está ISSUED") ElectronicDocument electronicDocument,
        @Schema(description = "Solo si la nota está REJECTED") String rejectionReason) {

    @Schema(description = "Línea acreditada: los datos de la línea de la factura con la cantidad acreditada")
    public record Line(
            @Schema(description = "Número de la línea en la factura (desde 1)") int invoiceLineNumber,
            String code,
            String name,
            @Schema(description = "Cantidad acreditada") BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal discountRate,
            ApiUnitOfMeasure unit,
            boolean ivaExcluded,
            List<InvoiceResponse.LineTax> taxes,
            BigDecimal grossAmount,
            BigDecimal discountAmount,
            BigDecimal taxableAmount,
            BigDecimal taxAmount,
            BigDecimal total) {
    }

    @Schema(description = "Documento electrónico validado. Con el proveedor falso, todos sus datos son falsos. "
            + "El PDF se descarga con GET /api/v1/credit-notes/{id}/pdf.")
    public record ElectronicDocument(
            String number,
            @Schema(description = "Código Único de Documento Electrónico (el equivalente al CUFE de la factura)")
            String cude,
            @Schema(description = "Consulta del documento en el catálogo de la DIAN (lo que codifica el QR)")
            String qrUrl,
            @Schema(description = "Vista pública del documento en el proveedor; quien tenga el enlace ve la nota")
            String publicUrl,
            Instant validatedAt,
            @Schema(description = "Total con validez legal, informado por el proveedor") BigDecimal officialTotal,
            @Schema(description = "true si el total oficial difiere del calculado; debe investigarse")
            boolean officialTotalDiscrepancy) {
    }
}
