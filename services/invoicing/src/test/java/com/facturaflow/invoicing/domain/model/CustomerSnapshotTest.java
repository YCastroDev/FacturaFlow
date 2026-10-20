package com.facturaflow.invoicing.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.invoicing.domain.exception.InvalidCustomerException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CustomerSnapshotTest {

    private static final IdentificationDocument CC = IdentificationDocument.of(DocumentType.CITIZENSHIP_CARD, "1020304050");

    @Test
    @DisplayName("una empresa se identifica por su razón social")
    void companyUsesCompanyName() {
        CustomerSnapshot customer = CustomerSnapshot.company(IdentificationDocument.nit("890903938", 8),
                "Bancolombia S.A.", null);

        assertThat(customer.displayName()).isEqualTo("Bancolombia S.A.");
        assertThat(customer.optionalEmail()).isEmpty();
    }

    @Test
    @DisplayName("una persona natural se identifica por sus nombres")
    void personUsesNames() {
        assertThat(CustomerSnapshot.person(CC, " Ana María Pérez ", "ana@example.com").displayName())
                .isEqualTo("Ana María Pérez");
    }

    @Test
    @DisplayName("un cliente sin razón social ni nombres se rechaza")
    void rejectsCustomerWithoutName() {
        assertThatThrownBy(() -> new CustomerSnapshot(CC, "  ", null, null))
                .isInstanceOf(InvalidCustomerException.class)
                .hasMessageContaining("razón social");
    }

    @Test
    @DisplayName("un cliente sin documento de identificación se rechaza")
    void rejectsCustomerWithoutIdentification() {
        assertThatThrownBy(() -> CustomerSnapshot.person(null, "Ana", null))
                .isInstanceOf(InvalidCustomerException.class);
    }

    @Test
    @DisplayName("un email vacío se trata como ausente")
    void blankEmailIsAbsent() {
        assertThat(CustomerSnapshot.person(CC, "Ana", "   ").optionalEmail()).isEmpty();
    }

    @ParameterizedTest(name = "''{0}''")
    @ValueSource(strings = {"ana", "ana@", "ana@example", "ana example@mail.com", "@example.com"})
    @DisplayName("si viene un email, debe tener formato válido")
    void rejectsInvalidEmail(String email) {
        assertThatThrownBy(() -> CustomerSnapshot.person(CC, "Ana", email))
                .isInstanceOf(InvalidCustomerException.class)
                .hasMessageContaining("email");
    }
}
