package com.facturaflow.invoicing.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.invoicing.domain.exception.InvalidMunicipalityException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MunicipalityTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"11001", "05001", "68679", "76001", "88001", "99001"})
    @DisplayName("un código DIVIPOLA de 5 dígitos con departamento válido se acepta")
    void acceptsValidCodes(String code) {
        assertThat(Municipality.of(code).code()).isEqualTo(code);
    }

    @Test
    @DisplayName("el departamento son los 2 primeros dígitos y se ignoran espacios en los extremos")
    void departmentCode() {
        Municipality medellin = Municipality.of(" 05001 ");

        assertThat(medellin.code()).isEqualTo("05001");
        assertThat(medellin.departmentCode()).isEqualTo("05");
    }

    @ParameterizedTest(name = "''{0}''")
    @ValueSource(strings = {"", "1100", "110010", "11O01", "5001", "bogot"})
    @DisplayName("un código que no tiene exactamente 5 dígitos se rechaza")
    void rejectsBadFormat(String code) {
        assertThatThrownBy(() -> Municipality.of(code))
                .isInstanceOf(InvalidMunicipalityException.class)
                .hasMessageContaining("DIVIPOLA");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"00000", "01001", "12001", "98001"})
    @DisplayName("un código cuyo departamento no existe se rechaza")
    void rejectsUnknownDepartment(String code) {
        assertThatThrownBy(() -> Municipality.of(code))
                .isInstanceOf(InvalidMunicipalityException.class)
                .hasMessageContaining("departamento");
    }

    @Test
    @DisplayName("un municipio sin código se rechaza")
    void rejectsNull() {
        assertThatThrownBy(() -> Municipality.of(null)).isInstanceOf(InvalidMunicipalityException.class);
    }
}
