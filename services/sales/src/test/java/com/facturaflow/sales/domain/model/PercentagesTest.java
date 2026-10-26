package com.facturaflow.sales.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.sales.domain.exception.InvalidPercentageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** COPIA de {@code PercentagesTest} de invoicing (ADR 0012). */
class PercentagesTest {

    @ParameterizedTest(name = "{0}%")
    @ValueSource(strings = {"0", "5", "19", "8.25", "100"})
    @DisplayName("una tarifa de impuesto entre 0 y 100 es válida")
    void acceptsValidTaxRates(String value) {
        assertThat(TaxRate.of(value).percent()).isEqualByComparingTo(value);
    }

    @ParameterizedTest(name = "{0}%")
    @ValueSource(strings = {"0", "10", "33.33", "100"})
    @DisplayName("un descuento entre 0 y 100 es válido")
    void acceptsValidDiscountRates(String value) {
        assertThat(DiscountRate.of(value).percent()).isEqualByComparingTo(value);
    }

    @ParameterizedTest(name = "{0}%")
    @ValueSource(strings = {"-0.01", "100.01", "150"})
    @DisplayName("un impuesto fuera de 0 a 100 se rechaza")
    void rejectsTaxOutOfRange(String value) {
        assertThatThrownBy(() -> TaxRate.of(value))
                .isInstanceOf(InvalidPercentageException.class)
                .hasMessageContaining("impuesto");
    }

    @ParameterizedTest(name = "{0}%")
    @ValueSource(strings = {"-1", "100.01"})
    @DisplayName("un descuento fuera de 0 a 100 se rechaza")
    void rejectsDiscountOutOfRange(String value) {
        assertThatThrownBy(() -> DiscountRate.of(value))
                .isInstanceOf(InvalidPercentageException.class)
                .hasMessageContaining("descuento");
    }

    @ParameterizedTest(name = "{0}%")
    @ValueSource(strings = {"19.005", "0.001"})
    @DisplayName("los porcentajes admiten máximo dos decimales")
    void rejectsTooManyDecimals(String value) {
        assertThatThrownBy(() -> TaxRate.of(value)).isInstanceOf(InvalidPercentageException.class);
        assertThatThrownBy(() -> DiscountRate.of(value)).isInstanceOf(InvalidPercentageException.class);
    }
}
