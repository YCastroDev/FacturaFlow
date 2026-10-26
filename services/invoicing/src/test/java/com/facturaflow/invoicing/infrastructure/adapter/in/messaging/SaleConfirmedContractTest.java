package com.facturaflow.invoicing.infrastructure.adapter.in.messaging;

import static com.facturaflow.invoicing.infrastructure.adapter.in.messaging.SaleConfirmedEvents.modified;
import static com.facturaflow.invoicing.infrastructure.adapter.in.messaging.SaleConfirmedEvents.payload;
import static com.facturaflow.invoicing.infrastructure.adapter.in.messaging.SaleConfirmedEvents.sale;
import static org.assertj.core.api.Assertions.assertThat;

import com.facturaflow.invoicing.EventContracts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Los eventos con los que se prueba el consumidor son los del contrato real: se validan contra
 * {@code docs/events/sale-confirmed.v1.schema.json}, el mismo archivo que valida sales (ADR 0014).
 */
class SaleConfirmedContractTest {

    @Test
    @DisplayName("los eventos de prueba válidos cumplen el esquema que publica sales")
    void testEventsMatchTheContract() {
        assertThat(EventContracts.saleConfirmedV1Violations(sale("VENTA-1"))).isEmpty();
        assertThat(EventContracts.saleConfirmedV1Violations(sale("VENTA-2", "SIMULAR_RECHAZO"))).isEmpty();
        assertThat(EventContracts.saleConfirmedV1Violations(
                SaleConfirmedEvents.withSalesTotal(sale("VENTA-3"), "34966.00"))).isEmpty();
    }

    @Test
    @DisplayName("el evento inválido de los tests de la DLQ realmente incumple el contrato")
    void invalidTestEventBreaksTheContract() {
        String unknownPaymentMethod = modified(sale("VENTA-4"), json -> payload(json).put("paymentMethod", "BITCOIN"));

        assertThat(EventContracts.saleConfirmedV1Violations(unknownPaymentMethod)).isNotEmpty();
    }
}
