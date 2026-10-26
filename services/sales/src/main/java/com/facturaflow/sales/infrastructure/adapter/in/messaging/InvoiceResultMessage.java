package com.facturaflow.sales.infrastructure.adapter.in.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * Los tres eventos de resultado de invoicing v1 ({@code docs/events/invoice-*.v1.schema.json}) leídos con un solo
 * DTO tolerante: el {@code eventType} dice qué campos del payload aplican. Solo lo que sales usa; el resto
 * ({@code officialTotal}, {@code issueDate}…) se ignora. No sale de este paquete.
 */
record InvoiceResultMessage(UUID eventId, String eventType, Integer eventVersion, Instant occurredAt, String producer,
                            Payload payload) {

    /**
     * @param invoiceId     InvoiceIssued, InvoiceRejected
     * @param customer      InvoiceIssued, InvoiceRejected
     * @param number        InvoiceIssued
     * @param cufe          InvoiceIssued
     * @param reason        InvoiceRejected, InvoiceRequestRejected
     * @param saleId        InvoiceRequestRejected (puede ser nulo)
     */
    record Payload(UUID invoiceId, String referenceCode, Customer customer, String number, String cufe, String reason,
                   UUID saleId) {
    }

    record Customer(String documentType, String documentNumber) {
    }
}
