package com.facturaflow.sales.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.sales.domain.exception.InvalidSoldByException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SoldByTest {

    @Test
    @DisplayName("guarda el identificador y el nombre de usuario sin espacios a los lados")
    void stripsValues() {
        SoldBy soldBy = new SoldBy(" 7d3b0000-0000-4000-8000-0000000000a1 ", " vendedor.demo ");

        assertThat(soldBy.userId()).isEqualTo("7d3b0000-0000-4000-8000-0000000000a1");
        assertThat(soldBy.username()).isEqualTo("vendedor.demo");
    }

    @Test
    @DisplayName("el identificador no tiene que ser un UUID: sales no interpreta el del proveedor de identidad")
    void userIdIsOpaque() {
        assertThat(new SoldBy("f:abc:123", "vendedor.demo").userId()).isEqualTo("f:abc:123");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    @DisplayName("una venta sin el identificador de quien la hizo no es válida")
    void userIdIsRequired(String userId) {
        assertThatThrownBy(() -> new SoldBy(userId, "vendedor.demo"))
                .isInstanceOf(InvalidSoldByException.class).hasMessageContaining("identificador de usuario");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    @DisplayName("una venta sin el nombre de quien la hizo no es válida")
    void usernameIsRequired(String username) {
        assertThatThrownBy(() -> new SoldBy("7d3b0000-0000-4000-8000-0000000000a1", username))
                .isInstanceOf(InvalidSoldByException.class).hasMessageContaining("nombre de usuario");
    }

    @Test
    @DisplayName("el identificador y el nombre admiten máximo 255 caracteres (el tope de Keycloak)")
    void maxLength() {
        assertThat(new SoldBy("i".repeat(255), "u".repeat(255)).username()).hasSize(255);
        assertThatThrownBy(() -> new SoldBy("i".repeat(256), "vendedor.demo"))
                .isInstanceOf(InvalidSoldByException.class).hasMessageContaining("256");
        assertThatThrownBy(() -> new SoldBy("7d3b", "u".repeat(256)))
                .isInstanceOf(InvalidSoldByException.class).hasMessageContaining("255");
    }
}
