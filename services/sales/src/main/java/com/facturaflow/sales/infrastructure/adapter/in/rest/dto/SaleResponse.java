package com.facturaflow.sales.infrastructure.adapter.in.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Una venta con la copia de cliente y productos que se tomó de catalog al confirmarla, y los totales
 * calculados (con las reglas de invoicing; la factura tendrá los oficiales).
 */
@Schema(description = "Venta confirmada")
public record SaleResponse(
        UUID id,
        String referenceCode,
        ApiSaleStatus status,
        @Schema(description = "Fecha de la venta en Colombia")
        LocalDate saleDate,
        Instant createdAt,
        Customer customer,
        List<Line> lines,
        ApiPaymentMethod paymentMethod,
        PaymentTerms paymentTerms,
        String observation,
        Totals totals,
        @Schema(description = "La factura electrónica emitida; solo con status INVOICED")
        Invoice invoice,
        @Schema(description = "Por qué no se pudo facturar; solo con status INVOICE_FAILED")
        InvoiceFailure invoiceFailure) {

    @Schema(description = "Factura emitida por invoicing para la venta (ADR 0015)")
    public record Invoice(UUID invoiceId, @Schema(example = "SETP990000001") String number, String cufe,
                          Instant invoicedAt) {
    }

    @Schema(description = "Motivo por el que la venta no se facturó: rechazo de la DIAN o de la petición (ADR 0015)")
    public record InvoiceFailure(String reason,
                                 @Schema(description = "La factura rechazada, si llegó a existir") UUID rejectedInvoiceId,
                                 Instant failedAt) {
    }

    @Schema(description = "Copia del cliente de catalog al momento de la venta")
    public record Customer(UUID customerId, ApiDocumentType documentType, String documentNumber, Integer checkDigit,
                           ApiPersonType personType, String companyName, String names, String displayName,
                           ApiTaxScheme taxScheme, List<ApiFiscalResponsibility> fiscalResponsibilities,
                           String municipalityCode, String address, String phone, String email) {
    }

    @Schema(description = "Copia del producto de catalog + cantidad y descuento; valores ya redondeados")
    public record Line(int lineNumber, UUID productId, String code, String name, ApiUnitOfMeasure unit,
                       BigDecimal quantity,
                       @Schema(description = "Precio unitario neto (sin impuestos ni descuentos)") BigDecimal unitPrice,
                       BigDecimal discountRate, boolean ivaExcluded, List<Tax> taxes, BigDecimal grossAmount,
                       BigDecimal discountAmount, BigDecimal taxableAmount, BigDecimal taxAmount, BigDecimal total) {
    }

    public record Tax(ApiTaxType type, BigDecimal rate, BigDecimal amount) {
    }

    public record PaymentTerms(ApiPaymentTermsType type, LocalDate dueDate) {
    }

    @Schema(description = "subtotal - totalDiscount + totalTax = total")
    public record Totals(BigDecimal subtotal, BigDecimal totalDiscount, BigDecimal totalIva, BigDecimal totalInc,
                         BigDecimal totalTax, BigDecimal total) {
    }
}
