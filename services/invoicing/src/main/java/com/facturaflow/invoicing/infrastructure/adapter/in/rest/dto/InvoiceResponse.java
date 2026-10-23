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
        @Schema(description = "Solo si la factura está REJECTED") String rejectionReason,
        @Schema(description = "Solo si la factura está ISSUED: lo acreditado con notas crédito y el saldo")
        Credits credits) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Customer(ApiDocumentType documentType, String documentNumber, Integer checkDigit,
                           ApiPersonType personType, String companyName, String names, ApiTaxScheme taxScheme,
                           @Schema(description = "En el orden del enum") List<ApiFiscalResponsibility> fiscalResponsibilities,
                           String email, String municipalityCode, String address,
                           @Schema(description = "Normalizado: solo dígitos y '+' inicial") String phone) {
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

    @Schema(description = "Documento electrónico validado. Con el proveedor falso, todos sus datos son falsos. "
            + "El PDF se descarga con GET /api/v1/invoices/{id}/pdf.")
    public record ElectronicDocument(
            String number,
            String cufe,
            @Schema(description = "Consulta del documento en el catálogo de la DIAN (lo que codifica el QR)")
            String qrUrl,
            @Schema(description = "Vista pública del documento en el proveedor; quien tenga el enlace ve la factura")
            String publicUrl,
            Instant validatedAt,
            @Schema(description = "Total con validez legal, informado por el proveedor") BigDecimal officialTotal,
            @Schema(description = "true si el total oficial difiere del calculado; debe investigarse")
            boolean officialTotalDiscrepancy) {
    }

    /**
     * Notas crédito de una factura ISSUED (ADR 0009). La factura no cambia de estado al acreditarse;
     * aquí se ve cuánto se acreditó y cuánto queda. La regla se aplica en cantidades por línea.
     */
    @Schema(description = "Lo acreditado con notas crédito (las REJECTED no cuentan) y lo que queda por acreditar")
    public record Credits(
            @Schema(description = "Total de las notas ISSUED") BigDecimal issuedTotal,
            @Schema(description = "Total de las notas PENDING: ya reservado, aún sin emitir") BigDecimal pendingTotal,
            @Schema(description = "Saldo: valor de las cantidades que aún se pueden acreditar (con el redondeo por "
                    + "línea; puede diferir en centavos de total - acreditado)") BigDecimal balance,
            List<CreditedLine> lines) {
    }

    @Schema(description = "Cantidades de una línea: facturada, acreditada (notas PENDING e ISSUED) y por acreditar")
    public record CreditedLine(int lineNumber, BigDecimal invoicedQuantity, BigDecimal creditedQuantity,
                               BigDecimal remainingQuantity) {
    }
}
