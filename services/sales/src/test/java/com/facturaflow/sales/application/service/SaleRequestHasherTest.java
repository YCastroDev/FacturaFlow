package com.facturaflow.sales.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.facturaflow.sales.application.port.in.CreateSaleCommand;
import com.facturaflow.sales.domain.model.DocumentType;
import com.facturaflow.sales.domain.model.PaymentMethod;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;

class SaleRequestHasherTest {

    private static final CreateSaleCommand BASE = new CreateSaleCommand("VENTA-1", DocumentType.NIT, "890903938",
            List.of(new CreateSaleCommand.Line("CAFE-500", new BigDecimal("2"), new BigDecimal("10"))),
            PaymentMethod.CASH, new CreateSaleCommand.CashTerms(), "Nota");

    private static String hash(CreateSaleCommand command) {
        return SaleRequestHasher.hash(SaleRequest.from(command)).value();
    }

    private static CreateSaleCommand withLines(CreateSaleCommand.Line... lines) {
        return new CreateSaleCommand(BASE.referenceCode(), BASE.customerDocumentType(), BASE.customerDocumentNumber(),
                List.of(lines), BASE.paymentMethod(), BASE.paymentTerms(), BASE.observation());
    }

    static Stream<Arguments> sameRequest() {
        return Stream.of(
                Arguments.of("otro código de referencia (es la clave)", new CreateSaleCommand("VENTA-2", DocumentType.NIT,
                        "890903938", BASE.lines(), PaymentMethod.CASH, new CreateSaleCommand.CashTerms(), "Nota")),
                Arguments.of("código en minúsculas y con espacios", withLines(
                        new CreateSaleCommand.Line(" cafe-500 ", new BigDecimal("2"), new BigDecimal("10")))),
                Arguments.of("cantidad y descuento con otra escala", withLines(
                        new CreateSaleCommand.Line("CAFE-500", new BigDecimal("2.00"), new BigDecimal("10.0")))),
                Arguments.of("observación con espacios", new CreateSaleCommand("VENTA-1", DocumentType.NIT, " 890903938",
                        BASE.lines(), PaymentMethod.CASH, new CreateSaleCommand.CashTerms(), "  Nota ")));
    }

    static Stream<Arguments> differentRequest() {
        return Stream.of(
                Arguments.of("otra cantidad", withLines(new CreateSaleCommand.Line("CAFE-500", new BigDecimal("3"),
                        new BigDecimal("10")))),
                Arguments.of("sin descuento", withLines(new CreateSaleCommand.Line("CAFE-500", new BigDecimal("2"), null))),
                Arguments.of("otro cliente", new CreateSaleCommand("VENTA-1", DocumentType.NIT, "800197268", BASE.lines(),
                        PaymentMethod.CASH, new CreateSaleCommand.CashTerms(), "Nota")),
                Arguments.of("a crédito", new CreateSaleCommand("VENTA-1", DocumentType.NIT, "890903938", BASE.lines(),
                        PaymentMethod.CASH, new CreateSaleCommand.CreditTerms(LocalDate.of(2030, 1, 31)), "Nota")),
                Arguments.of("otro medio de pago", new CreateSaleCommand("VENTA-1", DocumentType.NIT, "890903938",
                        BASE.lines(), PaymentMethod.CREDIT_CARD, new CreateSaleCommand.CashTerms(), "Nota")),
                Arguments.of("sin observación", new CreateSaleCommand("VENTA-1", DocumentType.NIT, "890903938",
                        BASE.lines(), PaymentMethod.CASH, new CreateSaleCommand.CashTerms(), null)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sameRequest")
    @DisplayName("lo que no cambia la venta no cambia la huella")
    void sameHash(String why, CreateSaleCommand command) {
        assertThat(hash(command)).isEqualTo(hash(BASE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("differentRequest")
    @DisplayName("cualquier cambio de contenido cambia la huella")
    void differentHash(String why, CreateSaleCommand command) {
        assertThat(hash(command)).isNotEqualTo(hash(BASE));
    }
}
