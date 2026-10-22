package com.facturaflow.invoicing.infrastructure.adapter.in.rest;

/**
 * Ejemplos de petición para Swagger UI. Son text blocks (constantes de compilación), por eso
 * pueden usarse dentro de {@code @ExampleObject}. Los datos son ficticios pero válidos: el NIT
 * tiene su dígito de verificación correcto y los emails usan el dominio reservado {@code .example}.
 */
final class ApiExamples {

    static final String CASH_WITH_IVA = """
            {
              "referenceCode": "VENTA-2026-0001",
              "customer": {
                "documentType": "NIT",
                "documentNumber": "890903938",
                "checkDigit": 8,
                "personType": "LEGAL_ENTITY",
                "companyName": "Cliente Ejemplo S.A.S.",
                "taxScheme": "IVA",
                "fiscalResponsibilities": [ "LARGE_TAXPAYER", "SELF_WITHHOLDER" ],
                "email": "facturacion@cliente.example",
                "municipalityCode": "11001",
                "address": "Calle 10 # 20-30",
                "phone": "+57 601 555 1234"
              },
              "lines": [
                {
                  "code": "SKU-001",
                  "name": "Teclado inalámbrico",
                  "quantity": 2,
                  "unitPrice": 85000.00,
                  "discountRate": 10,
                  "unit": "UNIT",
                  "taxes": [ { "type": "IVA", "rate": 19 } ]
                }
              ],
              "paymentMethod": "CASH",
              "paymentTerms": { "type": "CASH" },
              "observation": "Venta de mostrador"
            }
            """;

    /** Moto de más de 200 cc: lleva IVA 19 % e INC 8 % en la misma línea. */
    static final String CREDIT_WITH_IVA_AND_INC = """
            {
              "referenceCode": "VENTA-2026-0002",
              "customer": {
                "documentType": "CITIZENSHIP_CARD",
                "documentNumber": "1020304050",
                "personType": "NATURAL_PERSON",
                "names": "Ana María Gómez",
                "taxScheme": "NOT_APPLICABLE",
                "fiscalResponsibilities": [ "NOT_RESPONSIBLE" ],
                "email": "ana.gomez@correo.example",
                "municipalityCode": "05001"
              },
              "lines": [
                {
                  "code": "MOTO-250",
                  "name": "Motocicleta 250 cc",
                  "quantity": 1,
                  "unitPrice": 12500000.00,
                  "unit": "UNIT",
                  "taxes": [ { "type": "IVA", "rate": 19 }, { "type": "INC", "rate": 8 } ]
                },
                {
                  "code": "CASCO-01",
                  "name": "Casco certificado",
                  "quantity": 1,
                  "unitPrice": 350000.00,
                  "unit": "UNIT",
                  "taxes": [ { "type": "IVA", "rate": 19 } ]
                }
              ],
              "paymentMethod": "BANK_TRANSFER",
              "paymentTerms": { "type": "CREDIT", "dueDate": "2030-01-31" },
              "observation": "Crédito a 30 días"
            }
            """;

    private ApiExamples() {
    }
}
