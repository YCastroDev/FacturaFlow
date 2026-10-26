package com.facturaflow.invoicing.infrastructure.adapter.in.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * {@code SaleConfirmed} v1 tal como lo publica sales ({@code docs/events/sale-confirmed.v1.schema.json}). DTO
 * del Anti-Corruption Layer: no sale de este paquete. Se lee con {@link SaleConfirmedJson}, tolerante a campos
 * desconocidos (un cambio compatible de sales no rompe a invoicing). Solo se exige lo que se usa: lo demás
 * (p. ej. {@code customerId}, {@code productId}) se ignora.
 */
record SaleConfirmedMessage(UUID eventId, String eventType, Integer eventVersion, Instant occurredAt, String producer,
                            Payload payload) {

    record Payload(UUID saleId, String referenceCode, LocalDate saleDate, Customer customer, List<Line> lines,
                   String paymentMethod, Terms paymentTerms, String observation, Totals totals) {
    }

    record Customer(String documentType, String documentNumber, Integer checkDigit, String personType,
                    String companyName, String names, String taxScheme, List<String> fiscalResponsibilities,
                    String email, String municipalityCode, String address, String phone) {
    }

    record Line(String code, String name, BigDecimal quantity, BigDecimal unitPrice, BigDecimal discountRate,
                String unit, Boolean ivaExcluded, List<Tax> taxes) {
    }

    record Tax(String type, BigDecimal rate) {
    }

    record Terms(String type, LocalDate dueDate) {
    }

    /** Informativos (ADR 0013, decisión 7): invoicing no los usa para calcular. */
    record Totals(BigDecimal subtotal, BigDecimal totalDiscount, List<TaxTotal> taxes, BigDecimal totalTax,
                  BigDecimal total) {
    }

    record TaxTotal(String type, BigDecimal amount) {
    }
}
