package com.facturaflow.sales.domain.model;

import static com.facturaflow.sales.domain.model.SaleFixtures.CUSTOMER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.sales.domain.exception.InvalidCustomerSnapshotException;
import com.facturaflow.sales.domain.exception.InvalidSaleException;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CustomerSnapshotTest {

    private static CustomerSnapshot person(String names, Set<FiscalResponsibility> responsibilities) {
        return new CustomerSnapshot(CUSTOMER_ID, DocumentType.CITIZENSHIP_CARD, "1020304050", null,
                PersonType.NATURAL_PERSON, null, names, TaxScheme.NOT_APPLICABLE, responsibilities, " ", null, null, "");
    }

    @Test
    @DisplayName("solo se exige la estructura: lo obligatorio presente y el nombre según el tipo de persona")
    void structuralValidation() {
        CustomerSnapshot snapshot = person("Ana Pérez", Set.of(FiscalResponsibility.NOT_RESPONSIBLE));

        assertThat(snapshot.displayName()).isEqualTo("Ana Pérez");
        assertThat(snapshot.municipalityCode()).as("vacío = ausente").isNull();
        assertThat(snapshot.email()).isNull();
        assertThatThrownBy(() -> person(null, Set.of(FiscalResponsibility.NOT_RESPONSIBLE)))
                .isInstanceOf(InvalidCustomerSnapshotException.class).hasMessageContaining("nombres");
        assertThatThrownBy(() -> person("Ana", Set.of())).isInstanceOf(InvalidCustomerSnapshotException.class)
                .hasMessageContaining("responsabilidades");
    }

    @Test
    @DisplayName("las reglas fiscales no se copian por tercera vez: las validan catalog e invoicing")
    void fiscalRulesAreNotRevalidated() {
        // NOT_RESPONSIBLE combinada sería un error en catalog e invoicing; aquí no se revisa (ADR 0012, D4).
        assertThat(person("Ana", Set.of(FiscalResponsibility.NOT_RESPONSIBLE, FiscalResponsibility.LARGE_TAXPAYER))
                .fiscalResponsibilities()).hasSize(2);
    }

    @Test
    @DisplayName("el documento de la petición se normaliza como en catalog y solo admite letras y dígitos")
    void customerDocumentRef() {
        assertThat(new CustomerDocumentRef(DocumentType.PASSPORT, " ab123456 ").number()).isEqualTo("AB123456");
        assertThatThrownBy(() -> new CustomerDocumentRef(DocumentType.NIT, "890.903.938"))
                .isInstanceOf(InvalidSaleException.class);
        assertThatThrownBy(() -> new CustomerDocumentRef(null, "890903938")).isInstanceOf(InvalidSaleException.class);
    }
}
