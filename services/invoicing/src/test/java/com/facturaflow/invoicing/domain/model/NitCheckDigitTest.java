package com.facturaflow.invoicing.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.invoicing.domain.exception.InvalidIdentificationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class NitCheckDigitTest {

    @ParameterizedTest(name = "{0}: {1}-{2}")
    @CsvSource({
            "DIAN,            800197268, 4",
            "Bancolombia,     890903938, 8",
            "Ecopetrol,       899999068, 1",
            "Grupo Éxito,     890900608, 9",
            "Banco de Bogotá, 860002964, 4",
            "SENA,            899999034, 1",
            "Avianca,         890100577, 6",
            "EPM,             890904996, 1"
    })
    @DisplayName("el dígito de verificación coincide con el de NITs públicos conocidos")
    void matchesKnownPublicNits(String company, String nit, int expectedCheckDigit) {
        assertThat(NitCheckDigit.calculate(nit)).as(company).isEqualTo(expectedCheckDigit);
    }

    @ParameterizedTest(name = "''{0}''")
    @ValueSource(strings = {"", "80019726A", "8001-9726", "1234567890123456"})
    @DisplayName("solo se calcula el dígito de verificación de NITs numéricos de hasta 15 dígitos")
    void rejectsInvalidNits(String nit) {
        assertThatThrownBy(() -> NitCheckDigit.calculate(nit)).isInstanceOf(InvalidIdentificationException.class);
    }
}
