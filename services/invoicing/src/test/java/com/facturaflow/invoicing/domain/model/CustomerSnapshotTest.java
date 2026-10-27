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
    private static final IdentificationDocument NIT = IdentificationDocument.nit("890903938", 8);
    private static final FiscalProfile NOT_RESPONSIBLE = FiscalProfile.notResponsible();

    private static CustomerSnapshot customer(PersonType type, String companyName, String names) {
        return new CustomerSnapshot(NIT, type, companyName, names, NOT_RESPONSIBLE, null, null, null, null);
    }

    @Test
    @DisplayName("una persona jurídica se identifica por su razón social")
    void legalEntityUsesCompanyName() {
        CustomerSnapshot customer = CustomerSnapshot.company(NIT, "Bancolombia S.A.", NOT_RESPONSIBLE, null);

        assertThat(customer.personType()).isEqualTo(PersonType.LEGAL_ENTITY);
        assertThat(customer.displayName()).isEqualTo("Bancolombia S.A.");
        assertThat(customer.optionalEmail()).isEmpty();
    }

    @Test
    @DisplayName("una persona natural se identifica por sus nombres, aunque tenga NIT")
    void naturalPersonUsesNames() {
        CustomerSnapshot customer = CustomerSnapshot.person(NIT, " Ana María Pérez ", NOT_RESPONSIBLE, null);

        assertThat(customer.personType()).isEqualTo(PersonType.NATURAL_PERSON);
        assertThat(customer.displayName()).isEqualTo("Ana María Pérez");
    }

    @Test
    @DisplayName("una persona jurídica sin razón social se rechaza")
    void legalEntityRequiresCompanyName() {
        assertThatThrownBy(() -> customer(PersonType.LEGAL_ENTITY, "  ", null))
                .isInstanceOf(InvalidCustomerException.class)
                .hasMessageContaining("razón social");
    }

    @Test
    @DisplayName("una persona jurídica no puede tener nombres de persona")
    void legalEntityRejectsNames() {
        assertThatThrownBy(() -> customer(PersonType.LEGAL_ENTITY, "Empresa S.A.S.", "Ana"))
                .isInstanceOf(InvalidCustomerException.class)
                .hasMessageContaining("no debe tener nombres");
    }

    @Test
    @DisplayName("una persona natural sin nombres se rechaza")
    void naturalPersonRequiresNames() {
        assertThatThrownBy(() -> customer(PersonType.NATURAL_PERSON, null, " "))
                .isInstanceOf(InvalidCustomerException.class)
                .hasMessageContaining("requiere nombres");
    }

    @Test
    @DisplayName("una persona natural no puede tener razón social")
    void naturalPersonRejectsCompanyName() {
        assertThatThrownBy(() -> customer(PersonType.NATURAL_PERSON, "Empresa S.A.S.", "Ana"))
                .isInstanceOf(InvalidCustomerException.class)
                .hasMessageContaining("no debe tener razón social");
    }

    @Test
    @DisplayName("un cliente sin tipo de persona se rechaza")
    void rejectsMissingPersonType() {
        assertThatThrownBy(() -> customer(null, "Empresa", null))
                .isInstanceOf(InvalidCustomerException.class)
                .hasMessageContaining("tipo de persona");
    }

    @Test
    @DisplayName("un cliente sin situación tributaria se rechaza")
    void rejectsMissingFiscalProfile() {
        assertThatThrownBy(() -> CustomerSnapshot.company(NIT, "Empresa", null, null))
                .isInstanceOf(InvalidCustomerException.class)
                .hasMessageContaining("situación tributaria");
    }

    @Test
    @DisplayName("un cliente sin documento de identificación se rechaza")
    void rejectsCustomerWithoutIdentification() {
        assertThatThrownBy(() -> CustomerSnapshot.person(null, "Ana", NOT_RESPONSIBLE, null))
                .isInstanceOf(InvalidCustomerException.class);
    }

    @Test
    @DisplayName("email, municipio, dirección y teléfono son opcionales; los textos vacíos cuentan como ausentes")
    void optionalDataMayBeAbsent() {
        CustomerSnapshot customer = CustomerSnapshot.person(CC, "Ana", NOT_RESPONSIBLE, "   ")
                .withLocation(null, "  ", null);

        assertThat(customer.optionalEmail()).isEmpty();
        assertThat(customer.optionalMunicipality()).isEmpty();
        assertThat(customer.optionalAddress()).isEmpty();
        assertThat(customer.optionalPhone()).isEmpty();
    }

    @Test
    @DisplayName("la ubicación se conserva normalizada")
    void keepsLocation() {
        CustomerSnapshot customer = CustomerSnapshot.person(CC, "Ana", NOT_RESPONSIBLE, null)
                .withLocation(Municipality.of("05001"), "  Calle 10 # 20-30 ", PhoneNumber.of("(604) 444-5555"));

        assertThat(customer.optionalMunicipality()).contains(Municipality.of("05001"));
        assertThat(customer.optionalAddress()).contains("Calle 10 # 20-30");
        assertThat(customer.optionalPhone()).contains(PhoneNumber.of("6044445555"));
    }

    @Test
    @DisplayName("la dirección admite máximo 250 caracteres")
    void addressMaxLength() {
        CustomerSnapshot base = CustomerSnapshot.person(CC, "Ana", NOT_RESPONSIBLE, null);

        assertThat(base.withLocation(null, "a".repeat(250), null).address()).hasSize(250);
        assertThatThrownBy(() -> base.withLocation(null, "a".repeat(251), null))
                .isInstanceOf(InvalidCustomerException.class)
                .hasMessageContaining("250");
    }

    @ParameterizedTest(name = "''{0}''")
    @ValueSource(strings = {"ana", "ana@", "ana@example", "ana example@mail.com", "@example.com", "ana@mail..com",
            "ana@.mail.com", "ana@mail.com.", "ana@b@mail.com"})
    @DisplayName("si viene un email, debe tener formato válido")
    void rejectsInvalidEmail(String email) {
        assertThatThrownBy(() -> CustomerSnapshot.person(CC, "Ana", NOT_RESPONSIBLE, email))
                .isInstanceOf(InvalidCustomerException.class)
                .hasMessageContaining("email");
    }

    @ParameterizedTest(name = "''{0}''")
    @ValueSource(strings = {"ana@mail.com", "ana.perez+ventas@empresa.com.co", "a@b.c"})
    @DisplayName("un email con dominio y subdominios bien formados se acepta")
    void acceptsValidEmail(String email) {
        assertThat(CustomerSnapshot.person(CC, "Ana", NOT_RESPONSIBLE, email).optionalEmail()).contains(email);
    }
}
