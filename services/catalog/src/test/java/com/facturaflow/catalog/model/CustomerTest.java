package com.facturaflow.catalog.model;

import static com.facturaflow.catalog.model.CatalogFixtures.LATER;
import static com.facturaflow.catalog.model.CatalogFixtures.NOW;
import static com.facturaflow.catalog.model.CatalogFixtures.company;
import static com.facturaflow.catalog.model.CatalogFixtures.nit;
import static com.facturaflow.catalog.model.CatalogFixtures.person;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CustomerTest {

    private static CustomerProfile profile(PersonType type, String companyName, String names,
                                           Set<FiscalResponsibility> responsibilities) {
        return new CustomerProfile(type, companyName, names, TaxScheme.IVA, responsibilities, null, null, null, null);
    }

    private static CustomerProfile withLocation(String municipality, String address, String phone, String email) {
        return new CustomerProfile(PersonType.LEGAL_ENTITY, "Empresa", null, TaxScheme.IVA,
                Set.of(FiscalResponsibility.LARGE_TAXPAYER), municipality, address, phone, email);
    }

    @Nested
    @DisplayName("documento (copia de las reglas de invoicing)")
    class Document {

        @Test
        @DisplayName("el NIT exige su dígito de verificación correcto")
        void nitRequiresCorrectCheckDigit() {
            assertThat(CustomerDocument.nit("890903938", 8).number()).isEqualTo("890903938");
            assertThatThrownBy(() -> CustomerDocument.nit("890903938", 7))
                    .isInstanceOf(CatalogRuleException.class).hasMessageContaining("es 8, no 7");
            assertThatThrownBy(() -> new CustomerDocument(DocumentType.NIT, "890903938", null))
                    .isInstanceOf(CatalogRuleException.class).hasMessageContaining("dígito de verificación");
        }

        @Test
        @DisplayName("los demás documentos no llevan dígito de verificación")
        void otherDocumentsHaveNoCheckDigit() {
            assertThatThrownBy(() -> new CustomerDocument(DocumentType.CITIZENSHIP_CARD, "1020304050", 3))
                    .isInstanceOf(CatalogRuleException.class).hasMessageContaining("solo aplica al NIT");
        }

        @ParameterizedTest(name = "''{0}''")
        @ValueSource(strings = {"12", "1234567890123456", "12A45", "12 345"})
        @DisplayName("una cédula tiene de 3 a 15 dígitos")
        void numericDocumentFormat(String number) {
            assertThatThrownBy(() -> CustomerDocument.of(DocumentType.CITIZENSHIP_CARD, number))
                    .isInstanceOf(CatalogRuleException.class);
        }

        @Test
        @DisplayName("un pasaporte es alfanumérico y se guarda en mayúsculas y sin espacios a los lados")
        void passportIsAlphanumericAndUppercase() {
            assertThat(CustomerDocument.of(DocumentType.PASSPORT, "  ab123456 ").number()).isEqualTo("AB123456");
            assertThatThrownBy(() -> CustomerDocument.of(DocumentType.PASSPORT, "AB-123"))
                    .isInstanceOf(CatalogRuleException.class);
        }
    }

    @Nested
    @DisplayName("tipo de persona y situación tributaria")
    class Profile {

        @Test
        @DisplayName("una persona jurídica tiene solo razón social; una natural, solo nombres")
        void personTypeIsStrict() {
            Set<FiscalResponsibility> any = Set.of(FiscalResponsibility.LARGE_TAXPAYER);
            assertThatThrownBy(() -> profile(PersonType.LEGAL_ENTITY, null, "Ana", any))
                    .isInstanceOf(CatalogRuleException.class).hasMessageContaining("razón social");
            assertThatThrownBy(() -> profile(PersonType.LEGAL_ENTITY, "Empresa", "Ana", any))
                    .isInstanceOf(CatalogRuleException.class).hasMessageContaining("no envíe names");
            assertThatThrownBy(() -> profile(PersonType.NATURAL_PERSON, "Empresa", null, any))
                    .isInstanceOf(CatalogRuleException.class).hasMessageContaining("nombres");
            assertThatThrownBy(() -> profile(PersonType.NATURAL_PERSON, "Empresa", "Ana", any))
                    .isInstanceOf(CatalogRuleException.class).hasMessageContaining("no envíe companyName");
            assertThat(profile(PersonType.NATURAL_PERSON, "  ", "Ana", any).companyName())
                    .as("un texto vacío es ausente").isNull();
        }

        @Test
        @DisplayName("al menos una responsabilidad fiscal, y NOT_RESPONSIBLE va sola")
        void fiscalResponsibilities() {
            assertThatThrownBy(() -> profile(PersonType.LEGAL_ENTITY, "Empresa", null, Set.of()))
                    .isInstanceOf(CatalogRuleException.class);
            assertThatThrownBy(() -> profile(PersonType.LEGAL_ENTITY, "Empresa", null,
                    EnumSet.of(FiscalResponsibility.NOT_RESPONSIBLE, FiscalResponsibility.LARGE_TAXPAYER)))
                    .isInstanceOf(CatalogRuleException.class).hasMessageContaining("no se combina");
        }

        @Test
        @DisplayName("el municipio es un DIVIPOLA de 5 dígitos con departamento existente")
        void municipality() {
            assertThat(withLocation("11001", null, null, null).municipalityCode()).isEqualTo("11001");
            assertThatThrownBy(() -> withLocation("1100", null, null, null)).isInstanceOf(CatalogRuleException.class);
            assertThatThrownBy(() -> withLocation("02001", null, null, null))
                    .isInstanceOf(CatalogRuleException.class).hasMessageContaining("departamento");
        }

        @Test
        @DisplayName("el teléfono se normaliza y el email y la dirección se validan")
        void contactData() {
            assertThat(withLocation(null, null, "+57 (601) 555-12.34", null).phone()).isEqualTo("+576015551234");
            assertThatThrownBy(() -> withLocation(null, null, "12345", null)).isInstanceOf(CatalogRuleException.class);
            assertThatThrownBy(() -> withLocation(null, null, null, "sin-arroba")).isInstanceOf(CatalogRuleException.class);
            assertThatThrownBy(() -> withLocation(null, "D".repeat(251), null, null))
                    .isInstanceOf(CatalogRuleException.class).hasMessageContaining("250");
        }

        @ParameterizedTest(name = "''{0}''")
        @ValueSource(strings = {"ana", "ana@", "ana@example", "ana example@mail.com", "@example.com", "ana@mail..com",
                "ana@.mail.com", "ana@mail.com.", "ana@b@mail.com"})
        @DisplayName("si viene un email, debe tener formato válido (copia de la regla de invoicing)")
        void rejectsInvalidEmail(String email) {
            assertThatThrownBy(() -> withLocation(null, null, null, email))
                    .isInstanceOf(CatalogRuleException.class).hasMessageContaining("email");
        }

        @ParameterizedTest(name = "''{0}''")
        @ValueSource(strings = {"ana@mail.com", "ana.perez+ventas@empresa.com.co", "a@b.c"})
        @DisplayName("un email con dominio y subdominios bien formados se acepta")
        void acceptsValidEmail(String email) {
            assertThat(withLocation(null, null, null, email).email()).isEqualTo(email);
        }
    }

    @Nested
    @DisplayName("ciclo de vida")
    class Lifecycle {

        @Test
        @DisplayName("actualizar reemplaza el perfil; con los mismos datos no cambia nada")
        void update() {
            Customer customer = new Customer(nit(), company(), NOW);

            assertThat(customer.update(nit(), company(), LATER)).isFalse();
            assertThat(customer.updatedAt()).isEqualTo(NOW);
            assertThat(customer.update(nit(), company("Nuevo Nombre S.A.S."), LATER)).isTrue();
            assertThat(customer.profile().displayName()).isEqualTo("Nuevo Nombre S.A.S.");
            assertThat(customer.updatedAt()).isEqualTo(LATER);
        }

        @Test
        @DisplayName("el documento no se puede cambiar")
        void documentIsImmutable() {
            Customer customer = new Customer(nit(), company(), NOW);

            assertThatThrownBy(() -> customer.update(CustomerDocument.nit("800197268", 4), company(), LATER))
                    .isInstanceOf(CatalogRuleException.class).hasMessageContaining("documento");
        }

        @Test
        @DisplayName("una persona natural puede pasar a jurídica (el tipo de persona no es identidad)")
        void personTypeCanChange() {
            Customer customer = new Customer(CustomerDocument.of(DocumentType.CITIZENSHIP_CARD, "1020304050"),
                    person("Ana Pérez"), NOW);

            customer.update(CustomerDocument.of(DocumentType.CITIZENSHIP_CARD, "1020304050"), company("Ana SAS"), LATER);

            assertThat(customer.profile().personType()).isEqualTo(PersonType.LEGAL_ENTITY);
            assertThat(customer.profile().names()).isNull();
        }

        @Test
        @DisplayName("desactivar y reactivar son idempotentes")
        void deactivate() {
            Customer customer = new Customer(nit(), company(), NOW);

            assertThat(customer.deactivate(LATER)).isTrue();
            assertThat(customer.deactivate(LATER)).isFalse();
            assertThat(customer.active()).isFalse();
            assertThat(customer.activate(LATER)).isTrue();
        }
    }
}
