package com.facturaflow.invoicing.infrastructure.adapter.in.messaging;

import static com.facturaflow.invoicing.infrastructure.adapter.in.messaging.SaleConfirmedEvents.modified;
import static com.facturaflow.invoicing.infrastructure.adapter.in.messaging.SaleConfirmedEvents.payload;
import static com.facturaflow.invoicing.infrastructure.adapter.in.messaging.SaleConfirmedEvents.sale;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.invoicing.application.port.in.CreateInvoiceCommand;
import com.facturaflow.invoicing.domain.model.DocumentType;
import com.facturaflow.invoicing.domain.model.FiscalResponsibility;
import com.facturaflow.invoicing.domain.model.PaymentMethod;
import com.facturaflow.invoicing.domain.model.PersonType;
import com.facturaflow.invoicing.domain.model.TaxScheme;
import com.facturaflow.invoicing.domain.model.TaxType;
import com.facturaflow.invoicing.domain.model.UnitOfMeasure;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.node.ObjectNode;

class SaleConfirmedTranslatorTest {

    private final SaleConfirmedTranslator translator = new SaleConfirmedTranslator();

    private CreateInvoiceCommand translate(String event) {
        return translator.toCommand(SaleConfirmedJson.MAPPER.readValue(event, SaleConfirmedMessage.class));
    }

    @Test
    @DisplayName("el evento se traduce al mismo comando que usa la API REST, con el referenceCode de la venta")
    void translatesToCreateInvoiceCommand() {
        CreateInvoiceCommand command = translate(sale("VENTA-2026-0001", "Entregar en portería"));

        assertThat(command.referenceCode()).isEqualTo("VENTA-2026-0001");
        assertThat(command.customer()).isEqualTo(new CreateInvoiceCommand.Customer(DocumentType.NIT, "890903938", 8,
                PersonType.LEGAL_ENTITY, "Cliente Ejemplo S.A.S.", null, TaxScheme.IVA,
                List.of(FiscalResponsibility.LARGE_TAXPAYER, FiscalResponsibility.SELF_WITHHOLDER),
                "facturacion@cliente.example", "05001", "Carrera 48 # 10-45", "+576044445555"));
        CreateInvoiceCommand.Line line = command.lines().getFirst();
        assertThat(line.code()).isEqualTo("CAFE-500");
        assertThat(line.name()).isEqualTo("Café molido 500 g");
        assertThat(line.quantity()).isEqualByComparingTo("2");
        assertThat(line.unitPrice()).isEqualByComparingTo("18500");
        assertThat(line.discountRate()).isEqualByComparingTo("10");
        assertThat(line.unit()).isEqualTo(UnitOfMeasure.UNIT);
        assertThat(line.ivaExcluded()).isFalse();
        assertThat(line.taxes()).containsExactly(new CreateInvoiceCommand.Tax(TaxType.IVA, new BigDecimal("5.00")));
        assertThat(command.paymentMethod()).isEqualTo(PaymentMethod.CASH);
        assertThat(command.paymentTerms()).isEqualTo(new CreateInvoiceCommand.CashTerms());
        assertThat(command.observation()).isEqualTo("Entregar en portería");
    }

    @Test
    @DisplayName("una venta a crédito conserva su vencimiento; los campos que el contrato agregue se ignoran")
    void creditTermsAndUnknownFields() {
        String credit = modified(sale("VENTA-CREDITO"), json -> {
            ((ObjectNode) payload(json).get("paymentTerms")).put("type", "CREDIT").put("dueDate", "2026-11-25");
            payload(json).put("campoNuevoCompatible", "se ignora");
        });

        assertThat(translate(credit).paymentTerms())
                .isEqualTo(new CreateInvoiceCommand.CreditTerms(LocalDate.of(2026, 11, 25)));
    }

    static Stream<Arguments> invalidEvents() {
        return Stream.of(
                Arguments.of("otro tipo de evento", (Consumer<ObjectNode>) json -> json.put("eventType", "SaleCancelled")),
                Arguments.of("otra versión", (Consumer<ObjectNode>) json -> json.put("eventVersion", 2)),
                Arguments.of("sin payload", (Consumer<ObjectNode>) json -> json.remove("payload")),
                Arguments.of("sin cliente", (Consumer<ObjectNode>) json -> payload(json).remove("customer")),
                Arguments.of("sin líneas", (Consumer<ObjectNode>) json -> payload(json).remove("lines")),
                Arguments.of("medio de pago desconocido", (Consumer<ObjectNode>) json ->
                        payload(json).put("paymentMethod", "BITCOIN")),
                Arguments.of("forma de pago desconocida", (Consumer<ObjectNode>) json ->
                        ((ObjectNode) payload(json).get("paymentTerms")).put("type", "LEASING")),
                Arguments.of("unidad desconocida", (Consumer<ObjectNode>) json ->
                        ((ObjectNode) payload(json).get("lines").get(0)).put("unit", "BARRIL")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidEvents")
    @DisplayName("un evento fuera del contrato es inválido (irá a la DLQ sin reintentos)")
    void invalidEvents(String description, Consumer<ObjectNode> breakIt) {
        String event = modified(sale("VENTA-ROTA"), breakIt);

        assertThatThrownBy(() -> translate(event)).isInstanceOf(InvalidSaleConfirmedMessageException.class);
    }
}
