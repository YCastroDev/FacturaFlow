package com.facturaflow.sales.infrastructure.adapter.out.persistence;

import static com.facturaflow.sales.domain.model.SaleFixtures.CREATED_AT;
import static com.facturaflow.sales.domain.model.SaleFixtures.SALE_DATE;
import static com.facturaflow.sales.domain.model.SaleFixtures.SALE_ID;
import static com.facturaflow.sales.domain.model.SaleFixtures.customer;
import static com.facturaflow.sales.domain.model.SaleFixtures.excludedLine;
import static com.facturaflow.sales.domain.model.SaleFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.sales.EventContracts;
import com.facturaflow.sales.application.port.out.OutboxMessage;
import com.facturaflow.sales.domain.model.CustomerSnapshot;
import com.facturaflow.sales.domain.model.DocumentType;
import com.facturaflow.sales.domain.model.FiscalResponsibility;
import com.facturaflow.sales.domain.model.LineTax;
import com.facturaflow.sales.domain.model.PaymentMethod;
import com.facturaflow.sales.domain.model.PaymentTerms;
import com.facturaflow.sales.domain.model.PersonType;
import com.facturaflow.sales.domain.model.ReferenceCode;
import com.facturaflow.sales.domain.model.Sale;
import com.facturaflow.sales.domain.model.SaleEvent;
import com.facturaflow.sales.domain.model.SaleId;
import com.facturaflow.sales.domain.model.TaxScheme;
import com.facturaflow.sales.domain.model.TaxType;
import java.util.EnumSet;
import java.util.List;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * El contrato del evento (ADR 0013): lo que produce {@link SaleEventMessages} cumple
 * {@code docs/events/sale-confirmed.v1.schema.json}, el MISMO archivo que leerá invoicing (sin copias ni
 * librería compartida). Y el esquema no es permisivo de más: rechaza mensajes rotos.
 *
 * <p>El esquema se lee de la raíz del repo con {@link EventContracts}.
 */
class SaleConfirmedSchemaTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SaleEventMessages messages = new SaleEventMessages();

    private static CustomerSnapshot naturalPersonWithoutOptionals() {
        return new CustomerSnapshot(UUID.fromString("c0000000-0000-4000-8000-000000000002"),
                DocumentType.CITIZENSHIP_CARD, "1020304050", null, PersonType.NATURAL_PERSON, null, "Ana Pérez",
                TaxScheme.NOT_APPLICABLE, EnumSet.of(FiscalResponsibility.NOT_RESPONSIBLE), null, null, null, null);
    }

    /** Contado, persona jurídica, una línea con IVA + INC y otra excluida de IVA. */
    private static Sale cashSale() {
        return Sale.confirm(SALE_ID, new ReferenceCode("VENTA-0001"), SALE_DATE, CREATED_AT, customer(),
                List.of(line("2", "50000.00", "10", LineTax.of(TaxType.IVA, "19"), LineTax.of(TaxType.INC, "8")),
                        excludedLine("1.5", "4200")),
                PaymentMethod.CASH, PaymentTerms.cash(), "Entregar en portería");
    }

    /** Crédito, persona natural sin datos opcionales y sin observación. */
    private static Sale creditSale() {
        return Sale.confirm(new SaleId(UUID.fromString("5a1e0000-0000-4000-8000-000000000002")),
                new ReferenceCode("VENTA_0002"), SALE_DATE, CREATED_AT, naturalPersonWithoutOptionals(),
                List.of(excludedLine("3", "1000.50")), PaymentMethod.BANK_TRANSFER,
                PaymentTerms.credit(SALE_DATE.plusDays(30)), null);
    }

    static Stream<Arguments> sales() {
        return Stream.of(
                Arguments.of("contado, persona jurídica, IVA + INC y una línea excluida", cashSale()),
                Arguments.of("crédito, persona natural sin datos opcionales ni observación", creditSale()));
    }

    private OutboxMessage messageOf(Sale sale) {
        return messages.toMessage(sale.pendingEvents().getFirst(), sale);
    }

    private static List<String> violations(JsonNode message) {
        return EventContracts.saleConfirmedV1Violations(message);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sales")
    @DisplayName("el mensaje SaleConfirmed producido cumple el esquema del contrato")
    void producedMessagesMatchTheSchema(String description, Sale sale) {
        OutboxMessage message = messageOf(sale);

        assertThat(violations(JSON.readTree(message.payload()))).isEmpty();
        assertThat(message.eventType()).isEqualTo("SaleConfirmed");
        assertThat(message.eventVersion()).isEqualTo(1);
        assertThat(message.routingKey()).isEqualTo("sale.confirmed.v1");
    }

    @Test
    @DisplayName("el sobre lleva la identidad del evento y los datos van con los nombres de la API de invoicing")
    void envelopeAndPayload() {
        Sale sale = cashSale();
        OutboxMessage message = messageOf(sale);
        JsonNode json = JSON.readTree(message.payload());

        assertThat(json.propertyNames()).containsExactly("eventId", "eventType", "eventVersion", "occurredAt",
                "producer", "payload");
        assertThat(json.get("eventId").asString()).isEqualTo(message.eventId().toString());
        assertThat(json.get("occurredAt").asString()).isEqualTo("2026-10-23T15:00:00Z");
        assertThat(json.get("producer").asString()).isEqualTo("sales");
        JsonNode payload = json.get("payload");
        assertThat(payload.get("saleId").asString()).isEqualTo(SALE_ID.value().toString());
        assertThat(payload.get("referenceCode").asString()).isEqualTo("VENTA-0001");
        assertThat(payload.get("customer").get("companyName").asString()).isEqualTo("Cliente Ejemplo S.A.S.");
        assertThat(payload.get("customer").get("fiscalResponsibilities").toString())
                .isEqualTo("[\"LARGE_TAXPAYER\",\"SELF_WITHHOLDER\"]");
        assertThat(message.payload()).as("montos con 2 decimales, sin notación científica (el texto publicado)")
                .contains("\"unitPrice\":50000.00", "\"unitPrice\":4200.00");
        assertThat(payload.get("paymentTerms").toString()).isEqualTo("{\"type\":\"CASH\",\"dueDate\":null}");
        assertThat(payload.get("customer").has("municipalityCode")).isTrue();
    }

    @Test
    @DisplayName("los totales informativos son los de la venta en sales, con el desglose como el de invoicing")
    void informativeTotals() {
        Sale sale = cashSale();
        String payload = messageOf(sale).payload();
        JsonNode totals = JSON.readTree(payload).get("payload").get("totals");

        // Línea 1: 2 x 50.000 = 100.000 - 10 % = 90.000; IVA 19 % = 17.100; INC 8 % = 7.200.
        // Línea 2 (excluida): 1,5 x 4.200 = 6.300.
        assertThat(totals.get("subtotal").decimalValue()).isEqualByComparingTo(sale.subtotal().amount());
        assertThat(totals.get("totalDiscount").decimalValue()).isEqualByComparingTo("10000.00");
        assertThat(payload).contains(
                "\"taxes\":[{\"type\":\"IVA\",\"amount\":17100.00},{\"type\":\"INC\",\"amount\":7200.00}]");
        assertThat(totals.get("totalTax").decimalValue()).isEqualByComparingTo("24300.00");
        assertThat(totals.get("total").decimalValue()).isEqualByComparingTo(sale.total().amount())
                .isEqualByComparingTo("120600.00");
    }

    @Test
    @DisplayName("cada evento recibe un eventId distinto")
    void eventIdsAreUnique() {
        Sale sale = cashSale();

        assertThat(messageOf(sale).eventId()).isNotEqualTo(messageOf(sale).eventId());
    }

    static Stream<Arguments> brokenMessages() {
        return Stream.of(
                Arguments.of("sin los totales", (Consumer<ObjectNode>) json -> payload(json).remove("totals")),
                Arguments.of("un campo que el contrato no tiene", (Consumer<ObjectNode>) json ->
                        payload(json).put("unitPriceWithTax", 1)),
                Arguments.of("un medio de pago desconocido", (Consumer<ObjectNode>) json ->
                        payload(json).put("paymentMethod", "BITCOIN")),
                Arguments.of("crédito sin vencimiento", (Consumer<ObjectNode>) json ->
                        ((ObjectNode) payload(json).get("paymentTerms")).put("type", "CREDIT")),
                Arguments.of("contado con vencimiento", (Consumer<ObjectNode>) json ->
                        ((ObjectNode) payload(json).get("paymentTerms")).put("dueDate", "2026-12-01")),
                Arguments.of("persona jurídica sin razón social", (Consumer<ObjectNode>) json ->
                        ((ObjectNode) payload(json).get("customer")).putNull("companyName")),
                Arguments.of("sin líneas", (Consumer<ObjectNode>) json -> ((ArrayNode) payload(json).get("lines")).removeAll()),
                Arguments.of("un eventId que no es UUID", (Consumer<ObjectNode>) json -> json.put("eventId", "123")),
                Arguments.of("otra versión del evento", (Consumer<ObjectNode>) json -> json.put("eventVersion", 2)),
                Arguments.of("una fecha de venta que no es fecha", (Consumer<ObjectNode>) json ->
                        payload(json).put("saleDate", "23/10/2026")));
    }

    private static ObjectNode payload(ObjectNode json) {
        return (ObjectNode) json.get("payload");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("brokenMessages")
    @DisplayName("el esquema rechaza un mensaje roto")
    void schemaRejectsBrokenMessages(String description, Consumer<ObjectNode> breakIt) {
        Sale sale = cashSale();
        ObjectNode json = (ObjectNode) JSON.readTree(messageOf(sale).payload());

        breakIt.accept(json);

        assertThat(violations(json)).isNotEmpty();
    }

    @Test
    @DisplayName("el evento del mensaje es el de la venta: un evento de otra venta es un error de programación")
    void eventMustBelongToSale() {
        Sale sale = cashSale();
        SaleEvent foreign = new SaleEvent.SaleConfirmed(new SaleId(UUID.randomUUID()), CREATED_AT);

        assertThatThrownBy(() -> messages.toMessage(foreign, sale))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
