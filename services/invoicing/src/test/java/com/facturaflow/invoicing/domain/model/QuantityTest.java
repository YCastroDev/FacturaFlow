package com.facturaflow.invoicing.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.invoicing.domain.exception.InvalidQuantityException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class QuantityTest {

    @Test
    @DisplayName("una cantidad válida conserva su valor con la precisión del dominio")
    void acceptsValidQuantity() {
        assertThat(Quantity.of("1.5")).isEqualTo(Quantity.of("1.50"));
        assertThat(Quantity.of("3").value().scale()).isEqualTo(Quantity.MAX_DECIMALS);
    }

    @ParameterizedTest(name = "cantidad {0}")
    @ValueSource(strings = {"0", "0.00", "-1"})
    @DisplayName("la cantidad vendida debe ser mayor que cero")
    void rejectsNonPositive(String value) {
        assertThatThrownBy(() -> Quantity.of(value))
                .isInstanceOf(InvalidQuantityException.class)
                .hasMessageContaining("mayor que cero");
    }

    @Test
    @DisplayName("la cantidad admite máximo los decimales definidos en Quantity")
    void rejectsTooManyDecimals() {
        assertThatThrownBy(() -> Quantity.of("1.255"))
                .isInstanceOf(InvalidQuantityException.class)
                .hasMessageContaining(Quantity.MAX_DECIMALS + " decimales");
    }
}
