package com.facturaflow.invoicing.infrastructure.adapter.in.messaging;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Consumer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Eventos {@code SaleConfirmed} v1 de prueba, como los publica sales (los datos de scripts/seed-catalog.sh). Los
 * válidos cumplen {@code docs/events/sale-confirmed.v1.schema.json} ({@code SaleConfirmedContractTest} lo
 * comprueba): así los tests del consumidor no se apartan del contrato real.
 */
final class SaleConfirmedEvents {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 2 x 18.500 - 10 % = 33.300 + IVA 5 % (1.665) = 34.965: los totales que también calcula invoicing. */
    private static final String TEMPLATE = """
            {
              "eventId": "%s",
              "eventType": "SaleConfirmed",
              "eventVersion": 1,
              "occurredAt": "2026-10-26T16:38:58.404374822Z",
              "producer": "sales",
              "payload": {
                "saleId": "%s",
                "referenceCode": "%s",
                "saleDate": "2026-10-26",
                "customer": {
                  "customerId": "74c3ba4f-b067-416e-8255-39cfc3aa5d43",
                  "documentType": "NIT", "documentNumber": "890903938", "checkDigit": 8,
                  "personType": "LEGAL_ENTITY", "companyName": "Cliente Ejemplo S.A.S.", "names": null,
                  "taxScheme": "IVA", "fiscalResponsibilities": ["LARGE_TAXPAYER", "SELF_WITHHOLDER"],
                  "email": "facturacion@cliente.example", "municipalityCode": "05001",
                  "address": "Carrera 48 # 10-45", "phone": "+576044445555"
                },
                "lines": [
                  { "productId": "076af8f7-1a67-41a1-978e-0ba9af9cd1f4", "code": "CAFE-500", "name": "Café molido 500 g",
                    "quantity": 2.00, "unitPrice": 18500.00, "discountRate": 10.00, "unit": "UNIT", "ivaExcluded": false,
                    "taxes": [ { "type": "IVA", "rate": 5.00 } ] }
                ],
                "paymentMethod": "CASH",
                "paymentTerms": { "type": "CASH", "dueDate": null },
                "observation": %s,
                "totals": { "subtotal": 37000.00, "totalDiscount": 3700.00,
                            "taxes": [ { "type": "IVA", "amount": 1665.00 } ], "totalTax": 1665.00, "total": 34965.00 }
              }
            }""";

    private SaleConfirmedEvents() {
    }

    /** Venta de contado sin observación, con un eventId nuevo. */
    static String sale(String referenceCode) {
        return sale(referenceCode, null);
    }

    /** Con observación (p. ej. SIMULAR_CAIDA, SIMULAR_RECHAZO o SIMULAR_FALLA_TEMPORAL del proveedor falso). */
    static String sale(String referenceCode, String observation) {
        return TEMPLATE.formatted(UUID.randomUUID(), UUID.randomUUID(), referenceCode,
                observation == null ? "null" : "\"" + observation + "\"");
    }

    /** Un evento modificado (p. ej. totales distintos, campos rotos). */
    static String modified(String event, Consumer<ObjectNode> change) {
        ObjectNode json = (ObjectNode) JSON.readTree(event);
        change.accept(json);
        return JSON.writeValueAsString(json);
    }

    static ObjectNode payload(ObjectNode event) {
        return (ObjectNode) event.get("payload");
    }

    /** Otro total de sales (p. ej. un peso más): las copias del cálculo "divergieron". */
    static String withSalesTotal(String event, String total) {
        return modified(event, json -> ((ObjectNode) payload(json).get("totals")).put("total", new BigDecimal(total)));
    }
}
