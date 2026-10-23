package com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Contratos JSON de la API v2 de Factus, tal como se observaron en los spikes 0001 (facturas) y
 * 0002 (notas crédito). Son del
 * proveedor y no salen de este paquete (ADR 0008). Los nombres se traducen a snake_case con el
 * {@code JsonMapper} propio del adaptador ({@link FactusJson}); los campos que el adaptador no usa
 * se ignoran.
 */
final class FactusDtos {

    private FactusDtos() {
    }

    // ----- POST /oauth/token -----

    /** {@link #toString()} enmascara los tokens: nunca deben llegar a un log. */
    record TokenResponse(String tokenType, Long expiresIn, String accessToken, String refreshToken) {

        @Override
        public String toString() {
            return "TokenResponse[tokenType=" + tokenType + ", expiresIn=" + expiresIn
                    + ", accessToken=" + FactusMasking.mask(accessToken)
                    + ", refreshToken=" + FactusMasking.mask(refreshToken) + "]";
        }
    }

    // ----- POST /v2/bills/validate (petición) -----

    record BillRequest(String referenceCode, Integer numberingRangeId, String observation, boolean sendEmail,
                       Customer customer, List<Item> items, List<PaymentDetail> paymentDetails) {
    }

    record Customer(String identificationDocumentCode, String identification, String dv, String company,
                    String names, String legalOrganizationCode, String tributeCode, List<String> responsibilities,
                    String municipalityCode, String address, String email, String phone) {
    }

    record Item(String codeReference, String name, BigDecimal quantity, BigDecimal price, BigDecimal discountRate,
                String unitMeasureCode, String standardCode, List<ItemTax> taxes) {
    }

    /** {@code isExcluded} solo se envía en la línea excluida de IVA (tarifa 0). */
    record ItemTax(String code, BigDecimal rate, Boolean isExcluded) {
    }

    record PaymentDetail(String paymentForm, String paymentMethodCode, BigDecimal amount, String dueDate) {
    }

    // ----- POST /v2/bills/validate (respuesta 200/201) -----

    record BillResponse(String status, String message, BillData data) {
    }

    /**
     * {@code errors} es un {@link JsonNode} porque cambia de forma: un objeto de notificaciones
     * DIAN ({@code "FAJ43b": "..."}) o un arreglo vacío cuando no hay ninguna (PHP).
     * Los montos llegan como texto ({@code "182070.00"}).
     */
    record BillData(String referenceCode, String number, Boolean isValidated, String validatedAt, JsonNode errors,
                    ResponseCustomer customer, Totals totals, String cufe, Links links) {
    }

    record ResponseCustomer(Coded identificationDocument, String identification, String dv) {
    }

    record Coded(String code, String name) {
    }

    record Totals(String total) {
    }

    record Links(String qr, String publicUrl) {
    }

    // ----- POST /v2/credit-notes/validate (spike 0002) -----

    /**
     * Petición de nota crédito. Reutiliza el cliente, los ítems y los pagos de la factura: Factus exige
     * todos los datos y no toma nada de la factura referenciada (spike 0002, sección 2).
     */
    record CreditNoteRequest(String referenceCode, String correctionConceptCode, String customizationId,
                             String billNumber, Integer numberingRangeId, String observation, boolean sendEmail,
                             Customer customer, List<Item> items, List<PaymentDetail> paymentDetails) {
    }

    record CreditNoteResponse(String status, String message, CreditNoteData data) {
    }

    /**
     * Como {@link BillData}, con tres diferencias reales: el código único es {@code cude}, {@code errors}
     * es una LISTA de textos ({@code "Regla: CBF02, Notificación: ..."}) y {@code bill} identifica la
     * factura acreditada.
     */
    record CreditNoteData(String referenceCode, String number, Boolean isValidated, String validatedAt,
                          JsonNode errors, ResponseCustomer customer, Totals totals, String cude,
                          CreditedBill bill, Links links) {
    }

    record CreditedBill(String referenceCode, String number, String cufe) {
    }

    // ----- 422 (formato de Laravel: ruta del campo -> lista de mensajes) -----

    record ValidationErrorResponse(String status, String message, ValidationErrorData data) {
    }

    record ValidationErrorData(String message, JsonNode errors) {
    }

    // ----- GET /v2/bills/{number}/download-pdf y /v2/credit-notes/{number}/download-pdf (misma forma) -----

    record PdfResponse(String status, String message, PdfData data) {
    }

    record PdfData(String fileName, @JsonProperty("pdf_base_64_encoded") String pdfBase64) {
    }
}
