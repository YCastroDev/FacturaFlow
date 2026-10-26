package com.facturaflow.invoicing.infrastructure.adapter.out.persistence;

import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.electronicDocument;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.pendingInvoice;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.invoicing.EventContracts;
import com.facturaflow.invoicing.application.port.out.OutboxMessage;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.domain.model.InvoiceEvent;
import com.facturaflow.invoicing.domain.model.InvoiceId;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Los contratos de los eventos de facturación (ADR 0015): lo que produce {@link InvoiceEventMessages} cumple
 * {@code docs/events/invoice-*.v1.schema.json}, los MISMOS archivos que valida sales; y los esquemas rechazan
 * mensajes rotos.
 */
class InvoiceEventsSchemaTest {

    private static final Instant NOW = Instant.parse("2026-10-26T18:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final InvoiceEventMessages messages = new InvoiceEventMessages();

    private OutboxMessage issued() {
        Invoice invoice = pendingInvoice();
        invoice.markAsIssued(electronicDocument("119000.00"));
        return messages.toMessage(invoice.pendingEvents().getFirst(), invoice, NOW);
    }

    private OutboxMessage rejected() {
        Invoice invoice = pendingInvoice();
        invoice.markAsRejected("La DIAN no validó el documento");
        return messages.toMessage(invoice.pendingEvents().getFirst(), invoice, NOW);
    }

    private OutboxMessage requestRejected(UUID saleId, UUID sourceEventId) {
        return messages.toMessage(new InvoiceEvent.InvoiceRequestRejected("VENTA-0001", saleId, sourceEventId,
                "La fecha de vencimiento debe ser posterior a la fecha de emisión"), NOW);
    }

    @Test
    @DisplayName("InvoiceIssued cumple su esquema y lleva número, CUFE, total oficial y fechas, sin enlaces")
    void invoiceIssued() {
        OutboxMessage message = issued();
        JsonNode payload = JSON.readTree(message.payload()).get("payload");

        assertThat(EventContracts.invoiceEventViolations(message.payload())).isEmpty();
        assertThat(message.routingKey()).isEqualTo("invoice.issued.v1");
        assertThat(payload.get("referenceCode").asString()).isEqualTo("SALE-0001");
        assertThat(payload.get("number").asString()).isEqualTo("SETP990000001");
        assertThat(payload.get("cufe").asString()).hasSize(96);
        assertThat(payload.get("customer").toString()).isEqualTo("{\"documentType\":\"NIT\",\"documentNumber\":\"890903938\"}");
        assertThat(message.payload()).contains("\"officialTotal\":119000.00").doesNotContain("http");
        assertThat(JSON.readTree(message.payload()).get("occurredAt").asString()).isEqualTo("2026-10-26T18:00:00Z");
    }

    @Test
    @DisplayName("InvoiceRejected cumple su esquema y lleva el motivo")
    void invoiceRejected() {
        OutboxMessage message = rejected();

        assertThat(EventContracts.invoiceEventViolations(message.payload())).isEmpty();
        assertThat(message.routingKey()).isEqualTo("invoice.rejected.v1");
        assertThat(JSON.readTree(message.payload()).get("payload").get("reason").asString())
                .isEqualTo("La DIAN no validó el documento");
    }

    @Test
    @DisplayName("InvoiceRequestRejected cumple su esquema, con y sin los identificadores de la petición")
    void invoiceRequestRejected() {
        assertThat(EventContracts.invoiceEventViolations(requestRejected(UUID.randomUUID(), UUID.randomUUID()).payload()))
                .isEmpty();
        OutboxMessage anonymous = requestRejected(null, null);
        assertThat(EventContracts.invoiceEventViolations(anonymous.payload())).isEmpty();
        assertThat(anonymous.routingKey()).isEqualTo("invoice-request.rejected.v1");
        assertThat(anonymous.payload()).contains("\"saleId\":null");
    }

    @Test
    @DisplayName("un evento de otra factura, o una petición por la vía de los agregados, es un error de programación")
    void misuseIsRejected() {
        Invoice invoice = pendingInvoice();
        assertThatThrownBy(() -> messages.toMessage(new InvoiceEvent.InvoiceIssued(InvoiceId.generate()), invoice, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> messages.toMessage(new InvoiceEvent.InvoiceRequestRejected("V-1", null, null, "x"),
                invoice, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Arguments> brokenMessages() {
        return Stream.of(
                Arguments.of("emitida sin CUFE", "issued", (Consumer<ObjectNode>) json -> payload(json).remove("cufe")),
                Arguments.of("emitida con un CUFE que no es hexadecimal de 96", "issued",
                        (Consumer<ObjectNode>) json -> payload(json).put("cufe", "123")),
                Arguments.of("emitida con un campo extra (un enlace)", "issued",
                        (Consumer<ObjectNode>) json -> payload(json).put("publicUrl", "https://x")),
                Arguments.of("rechazada sin motivo", "rejected", (Consumer<ObjectNode>) json -> payload(json).remove("reason")),
                Arguments.of("otro productor", "rejected", (Consumer<ObjectNode>) json -> json.put("producer", "sales")),
                Arguments.of("petición rechazada con un saleId que no es UUID", "request",
                        (Consumer<ObjectNode>) json -> payload(json).put("saleId", "123")));
    }

    private static ObjectNode payload(ObjectNode json) {
        return (ObjectNode) json.get("payload");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("brokenMessages")
    @DisplayName("los esquemas rechazan un mensaje roto")
    void schemasRejectBrokenMessages(String description, String kind, Consumer<ObjectNode> breakIt) {
        OutboxMessage message = switch (kind) {
            case "issued" -> issued();
            case "rejected" -> rejected();
            default -> requestRejected(UUID.randomUUID(), UUID.randomUUID());
        };
        ObjectNode json = (ObjectNode) JSON.readTree(message.payload());

        breakIt.accept(json);

        assertThat(EventContracts.invoiceEventViolations(JSON.writeValueAsString(json))).isNotEmpty();
    }
}
