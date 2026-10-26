package com.facturaflow.sales.infrastructure.adapter.in.messaging;

import java.util.UUID;

/**
 * Eventos de resultado v1 de prueba, como los publica invoicing. Los tests comprueban que cumplen los esquemas de
 * {@code docs/events/} antes de usarlos: así el consumidor no se aparta del contrato real.
 */
final class InvoiceResultEvents {

    static final String CUFE = "ab".repeat(48);

    private InvoiceResultEvents() {
    }

    private static String envelope(String type, String payload) {
        return """
                { "eventId": "%s", "eventType": "%s", "eventVersion": 1, "occurredAt": "2026-10-26T18:00:00Z",
                  "producer": "invoicing", "payload": %s }""".formatted(UUID.randomUUID(), type, payload);
    }

    static String issued(String referenceCode, UUID invoiceId, String number) {
        return issued(referenceCode, invoiceId, number, "NIT", "890903938");
    }

    static String issued(String referenceCode, UUID invoiceId, String number, String documentType,
                         String documentNumber) {
        return envelope("InvoiceIssued", """
                { "invoiceId": "%s", "referenceCode": "%s",
                  "customer": { "documentType": "%s", "documentNumber": "%s" },
                  "number": "%s", "cufe": "%s", "officialTotal": 1190.00, "issueDate": "2026-10-26",
                  "validatedAt": "2026-10-26T17:59:58Z" }""".formatted(invoiceId, referenceCode, documentType,
                documentNumber, number, CUFE));
    }

    static String rejected(String referenceCode, UUID invoiceId, String reason) {
        return envelope("InvoiceRejected", """
                { "invoiceId": "%s", "referenceCode": "%s",
                  "customer": { "documentType": "NIT", "documentNumber": "890903938" },
                  "reason": "%s", "issueDate": "2026-10-26" }""".formatted(invoiceId, referenceCode, reason));
    }

    static String requestRejected(String referenceCode, UUID saleId, String reason) {
        return envelope("InvoiceRequestRejected", """
                { "referenceCode": "%s", "saleId": %s, "sourceEventId": null, "reason": "%s" }""".formatted(
                referenceCode, saleId == null ? "null" : "\"" + saleId + "\"", reason));
    }
}
