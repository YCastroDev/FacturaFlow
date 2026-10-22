package com.facturaflow.invoicing.domain.model;

import static com.facturaflow.invoicing.domain.model.FiscalResponsibility.LARGE_TAXPAYER;
import static com.facturaflow.invoicing.domain.model.FiscalResponsibility.NOT_RESPONSIBLE;
import static com.facturaflow.invoicing.domain.model.FiscalResponsibility.SELF_WITHHOLDER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.invoicing.domain.exception.InvalidCustomerException;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FiscalProfileTest {

    @Test
    @DisplayName("un cliente puede tener varias responsabilidades fiscales; repetirlas no cambia nada")
    void severalResponsibilities() {
        FiscalProfile profile = FiscalProfile.of(TaxScheme.IVA, LARGE_TAXPAYER, SELF_WITHHOLDER, LARGE_TAXPAYER);

        assertThat(profile.responsibilities()).containsExactlyInAnyOrder(LARGE_TAXPAYER, SELF_WITHHOLDER);
        assertThat(profile).isEqualTo(FiscalProfile.of(TaxScheme.IVA, SELF_WITHHOLDER, LARGE_TAXPAYER));
    }

    @Test
    @DisplayName("el consumidor final típico no es responsable de IVA ni de otras obligaciones")
    void notResponsible() {
        assertThat(FiscalProfile.notResponsible())
                .isEqualTo(new FiscalProfile(TaxScheme.NOT_APPLICABLE, Set.of(NOT_RESPONSIBLE)));
    }

    @Test
    @DisplayName("'no responsable' significa ninguna otra: no se combina")
    void notResponsibleCannotBeCombined() {
        assertThatThrownBy(() -> FiscalProfile.of(TaxScheme.NOT_APPLICABLE, NOT_RESPONSIBLE, LARGE_TAXPAYER))
                .isInstanceOf(InvalidCustomerException.class)
                .hasMessageContaining("no responsable");
    }

    @Test
    @DisplayName("se exige al menos una responsabilidad fiscal y una responsabilidad tributaria")
    void requiresSchemeAndResponsibilities() {
        assertThatThrownBy(() -> FiscalProfile.of(TaxScheme.IVA))
                .isInstanceOf(InvalidCustomerException.class)
                .hasMessageContaining("al menos una");
        assertThatThrownBy(() -> new FiscalProfile(TaxScheme.IVA, null))
                .isInstanceOf(InvalidCustomerException.class);
        assertThatThrownBy(() -> FiscalProfile.of(null, LARGE_TAXPAYER))
                .isInstanceOf(InvalidCustomerException.class)
                .hasMessageContaining("tributaria");
    }

    @Test
    @DisplayName("una responsabilidad nula se rechaza")
    void rejectsNullResponsibility() {
        Set<FiscalResponsibility> withNull = new HashSet<>();
        withNull.add(null);

        assertThatThrownBy(() -> new FiscalProfile(TaxScheme.IVA, withNull))
                .isInstanceOf(InvalidCustomerException.class);
    }

    @Test
    @DisplayName("el conjunto guardado no se puede modificar desde afuera")
    void responsibilitiesAreImmutable() {
        Set<FiscalResponsibility> original = new HashSet<>(Set.of(LARGE_TAXPAYER));
        FiscalProfile profile = new FiscalProfile(TaxScheme.IVA, original);
        original.add(SELF_WITHHOLDER);

        assertThat(profile.responsibilities()).containsExactly(LARGE_TAXPAYER);
        assertThatThrownBy(() -> profile.responsibilities().add(SELF_WITHHOLDER))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
