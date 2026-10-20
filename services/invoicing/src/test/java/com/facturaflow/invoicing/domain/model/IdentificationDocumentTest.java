package com.facturaflow.invoicing.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.invoicing.domain.exception.InvalidIdentificationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class IdentificationDocumentTest {

    @Test
    @DisplayName("un NIT con su dígito de verificación correcto es válido")
    void acceptsNitWithCorrectCheckDigit() {
        IdentificationDocument dian = IdentificationDocument.nit("800197268", 4);

        assertThat(dian.type()).isEqualTo(DocumentType.NIT);
        assertThat(dian.checkDigit()).isEqualTo(4);
    }

    @ParameterizedTest(name = "NIT 800197268-{0}")
    @ValueSource(ints = {0, 1, 3, 5, 9})
    @DisplayName("un NIT con dígito de verificación incorrecto se rechaza indicando el correcto")
    void rejectsNitWithWrongCheckDigit(int wrongCheckDigit) {
        assertThatThrownBy(() -> IdentificationDocument.nit("800197268", wrongCheckDigit))
                .isInstanceOf(InvalidIdentificationException.class)
                .hasMessageContaining("es 4");
    }

    @Test
    @DisplayName("un NIT sin dígito de verificación se rechaza")
    void rejectsNitWithoutCheckDigit() {
        assertThatThrownBy(() -> new IdentificationDocument(DocumentType.NIT, "800197268", null))
                .isInstanceOf(InvalidIdentificationException.class)
                .hasMessageContaining("requiere dígito de verificación");
    }

    @Test
    @DisplayName("solo el NIT lleva dígito de verificación")
    void rejectsCheckDigitOnOtherTypes() {
        assertThatThrownBy(() -> new IdentificationDocument(DocumentType.CITIZENSHIP_CARD, "1020304050", 3))
                .isInstanceOf(InvalidIdentificationException.class)
                .hasMessageContaining("Solo el NIT");
    }

    @ParameterizedTest(name = "{0} ''{1}''")
    @CsvSource({
            "CITIZENSHIP_CARD, 10A0304050",
            "CITIZENSHIP_CARD, 12",
            "CITIZENSHIP_CARD, 1234567890123456",
            "PASSPORT,         AB-12345",
            "NIT,              ''"
    })
    @DisplayName("un número de documento con formato inválido se rechaza")
    void rejectsInvalidNumbers(DocumentType type, String number) {
        Integer checkDigit = type == DocumentType.NIT ? 0 : null;
        assertThatThrownBy(() -> new IdentificationDocument(type, number, checkDigit))
                .isInstanceOf(InvalidIdentificationException.class)
                .hasMessageContaining("no es válido");
    }

    @Test
    @DisplayName("un pasaporte admite letras y números")
    void passportAcceptsLetters() {
        assertThat(IdentificationDocument.of(DocumentType.PASSPORT, "AB123456").number()).isEqualTo("AB123456");
    }

    @Test
    @DisplayName("el tipo de documento es obligatorio")
    void rejectsMissingType() {
        assertThatThrownBy(() -> IdentificationDocument.of(null, "1020304050"))
                .isInstanceOf(InvalidIdentificationException.class);
    }
}
