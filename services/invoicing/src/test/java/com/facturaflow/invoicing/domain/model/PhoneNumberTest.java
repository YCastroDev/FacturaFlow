package com.facturaflow.invoicing.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.invoicing.domain.exception.InvalidCustomerException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PhoneNumberTest {

    @ParameterizedTest(name = "''{0}'' → {1}")
    @CsvSource(delimiter = '|', value = {
            "3001234567          | 3001234567",
            "+57 300 123 4567    | +573001234567",
            "(604) 444-5555      | 6044445555",
            "601.555.1234        | 6015551234",
            "1234567             | 1234567",
            "+123456789012345    | +123456789012345"})
    @DisplayName("el teléfono se normaliza quitando separadores")
    void normalizes(String input, String expected) {
        assertThat(PhoneNumber.of(input).value()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "''{0}''")
    @ValueSource(strings = {"", "123456", "1234567890123456", "300-ABC-4567", "57+3001234567", "++573001234567"})
    @DisplayName("un teléfono con menos de 7 o más de 15 dígitos, letras o un '+' mal ubicado se rechaza")
    void rejectsInvalid(String input) {
        assertThatThrownBy(() -> PhoneNumber.of(input))
                .isInstanceOf(InvalidCustomerException.class)
                .hasMessageContaining("teléfono");
    }
}
