package com.facturaflow.invoicing.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.invoicing.domain.exception.InvalidReferenceCodeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ReferenceCodeTest {

    @Test
    @DisplayName("el código de referencia de una venta se guarda sin espacios alrededor")
    void stripsSurroundingSpaces() {
        assertThat(new ReferenceCode("  SALE-2026_0001 ").value()).isEqualTo("SALE-2026_0001");
    }

    @Test
    @DisplayName("dos códigos de referencia iguales representan la misma venta")
    void equalByValue() {
        assertThat(new ReferenceCode("SALE-1")).isEqualTo(new ReferenceCode("SALE-1"));
    }

    @ParameterizedTest(name = "''{0}''")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "con espacio", "VENTA-Ñ", "SALE/1",
            "123456789012345678901234567890123456789012345678901"})
    @DisplayName("un código de referencia vacío, muy largo o con caracteres inválidos se rechaza")
    void rejectsInvalidCodes(String value) {
        assertThatThrownBy(() -> new ReferenceCode(value)).isInstanceOf(InvalidReferenceCodeException.class);
    }
}
