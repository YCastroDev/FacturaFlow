package com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Representación de una factura en la API. Los montos los calcula el dominio; aquí solo se
 * copian. Los campos sin valor (p. ej. {@code electronicDocument} de una factura PENDING) se omiten.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Factura con sus totales calculados")
public record InvoiceResponse(
        UUID id,
        String referenceCode,
        ApiInvoiceStatus status,
        @Schema(description = "Fecha de emisión (fecha de Colombia)") LocalDate issueDate,
        Customer customer,
        ApiPaymentMethod paymentMethod,
        PaymentTerms paymentTerms,
        String observation,
        List<Line> lines,
        Totals totals,
        @Schema(description = "Solo si la factura está ISSUED") ElectronicDocument electronicDocument,
        @Schema(description = "Solo si la factura está REJECTED") String rejectionReason) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Customer(ApiDocumentType documentType, String documentNumber, Integer checkDigit,
                           String companyName, String names, String email) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PaymentTerms(ApiPaymentTermsType type, LocalDate dueDate) {
    }

    @Schema(description = "Línea con sus montos: bruto - descuento = base; base + impuestos = total")
    public record Line(
            @Schema(description = "Posición de la línea, desde 1") int lineNumber,
            String code,
            String name,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal discountRate,
            ApiUnitOfMeasure unit,
            boolean ivaExcluded,
            List<LineTax> taxes,
            BigDecimal grossAmount,
            BigDecimal discountAmount,
            BigDecimal taxableAmount,
            BigDecimal taxAmount,
            BigDecimal total) {
    }

    public record LineTax(ApiTaxType type, BigDecimal rate, BigDecimal amount) {
    }

    @Schema(description = "Totales calculados por el dominio (suma de valores ya redondeados por línea)")
    public record Totals(
            BigDecimal subtotal,
            BigDecimal totalDiscount,
            @Schema(description = "Desglose por tipo de impuesto presente en la factura") List<TaxTotal> taxes,
            BigDecimal totalTax,
            BigDecimal total) {
    }

    public record TaxTotal(ApiTaxType type, BigDecimal amount) {
    }

    @Schema(description = "Documento electrónico validado. Con el proveedor falso, todos sus datos son falsos.")
    public record ElectronicDocument(
            String number,
            String cufe,
            String pdfUrl,
            Instant validatedAt,
            @Schema(description = "Total con validez legal, informado por el proveedor") BigDecimal officialTotal,
            @Schema(description = "true si el total oficial difiere del calculado; debe investigarse")
            boolean officialTotalDiscrepancy) {
    }
}
