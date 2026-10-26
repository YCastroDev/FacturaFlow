package com.facturaflow.sales.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.sales.domain.exception.InvalidAmountException;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** COPIA de {@code MoneyTest} de invoicing (ADR 0012): los mismos casos, para que el cálculo coincida. */
class MoneyTest {

    @Test
    @DisplayName("un valor en pesos siempre queda con dos decimales")
    void normalizesToTwoDecimals() {
        assertThat(Money.of("100").amount().scale()).isEqualTo(2);
        assertThat(Money.of("100")).isEqualTo(Money.of("100.00"));
        assertThat(Money.of("10.500")).isEqualTo(Money.of("10.50"));
    }

    @Test
    @DisplayName("no existen valores monetarios negativos")
    void rejectsNegativeAmounts() {
        assertThatThrownBy(() -> Money.of("-0.01"))
                .isInstanceOf(InvalidAmountException.class)
                .hasMessageContaining("negativo");
    }

    @Test
    @DisplayName("un valor monetario es obligatorio")
    void rejectsMissingAmount() {
        assertThatThrownBy(() -> Money.of((BigDecimal) null)).isInstanceOf(InvalidAmountException.class);
    }

    @ParameterizedTest(name = "{0} tiene más de dos decimales")
    @ValueSource(strings = {"0.001", "10.005", "1999.999"})
    @DisplayName("un valor de entrada admite máximo dos decimales")
    void rejectsMoreThanTwoDecimals(String amount) {
        assertThatThrownBy(() -> Money.of(amount))
                .isInstanceOf(InvalidAmountException.class)
                .hasMessageContaining("2 decimales");
    }

    @ParameterizedTest(name = "{0} se redondea a {1}")
    @CsvSource({"0.005, 0.01", "0.004, 0.00", "2.345, 2.35", "0.025, 0.03", "299.9985, 300.00"})
    @DisplayName("los resultados de cálculos se redondean HALF_UP (comercial) a dos decimales")
    void roundsHalfUp(String raw, String expected) {
        assertThat(Money.rounded(new BigDecimal(raw))).isEqualTo(Money.of(expected));
    }

    @Test
    @DisplayName("restar más de lo que hay no produce un valor negativo")
    void rejectsNegativeSubtraction() {
        assertThatThrownBy(() -> Money.of("10").minus(Money.of("10.01")))
                .isInstanceOf(InvalidAmountException.class);
    }

    @Test
    @DisplayName("el porcentaje de un valor se redondea según la política del dominio")
    void percentageIsRounded() {
        assertThat(Money.of("1000").percentage(new BigDecimal("19"))).isEqualTo(Money.of("190"));
        assertThat(Money.of("10.05").percentage(new BigDecimal("10"))).isEqualTo(Money.of("1.01"));
    }
}
