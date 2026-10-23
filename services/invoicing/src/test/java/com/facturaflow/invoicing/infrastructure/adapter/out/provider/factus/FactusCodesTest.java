package com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus;

import static org.assertj.core.api.Assertions.assertThat;

import com.facturaflow.invoicing.domain.model.CreditNoteReason;
import com.facturaflow.invoicing.domain.model.DocumentType;
import com.facturaflow.invoicing.domain.model.FiscalResponsibility;
import com.facturaflow.invoicing.domain.model.PaymentMethod;
import com.facturaflow.invoicing.domain.model.PaymentTerms;
import com.facturaflow.invoicing.domain.model.PersonType;
import com.facturaflow.invoicing.domain.model.TaxScheme;
import com.facturaflow.invoicing.domain.model.TaxType;
import com.facturaflow.invoicing.domain.model.UnitOfMeasure;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Las tablas de equivalencia, una fila por valor del dominio, contra los códigos que el sandbox
 * aceptó en el spike 0001 (sección 9) y en el spike 0002 (sección 1, notas crédito). Si un código cambia aquí, hay que volver a verificarlo.
 */
class FactusCodesTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "CIVIL_REGISTRY, 11", "IDENTITY_CARD, 12", "CITIZENSHIP_CARD, 13", "FOREIGNER_CARD, 21",
            "FOREIGNER_ID_CARD, 22", "NIT, 31", "PASSPORT, 41", "FOREIGN_ID_DOCUMENT, 42",
            "SPECIAL_STAY_PERMIT, 47", "TEMPORARY_PROTECTION_PERMIT, 48", "FOREIGN_NIT, 50"})
    @DisplayName("cada tipo de documento tiene el código de la DIAN que acepta Factus")
    void documentTypes(DocumentType type, String code) {
        assertThat(FactusCodes.documentType(type)).isEqualTo(code);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"LEGAL_ENTITY, 1", "NATURAL_PERSON, 2"})
    @DisplayName("el tipo de persona se traduce a la organización jurídica")
    void personTypes(PersonType type, String code) {
        assertThat(FactusCodes.legalOrganization(type)).isEqualTo(code);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"IVA, 01", "NOT_APPLICABLE, ZZ"})
    @DisplayName("la responsabilidad tributaria se traduce al tributo del cliente")
    void taxSchemes(TaxScheme scheme, String code) {
        assertThat(FactusCodes.tribute(scheme)).isEqualTo(code);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"LARGE_TAXPAYER, O-13", "SELF_WITHHOLDER, O-15", "VAT_WITHHOLDING_AGENT, O-23",
            "SIMPLE_TAX_REGIME, O-47", "NOT_RESPONSIBLE, R-99-PN"})
    @DisplayName("cada responsabilidad fiscal tiene su código del RUT")
    void fiscalResponsibilities(FiscalResponsibility responsibility, String code) {
        assertThat(FactusCodes.responsibility(responsibility)).isEqualTo(code);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"UNIT, 94", "KILOGRAM, KGM", "GRAM, GRM", "POUND, LBR", "LITER, LTR", "MILLILITER, MLT",
            "GALLON, GLL", "METER, MTR"})
    @DisplayName("cada unidad de medida tiene uno de los 8 códigos que Factus acepta")
    void unitsOfMeasure(UnitOfMeasure unit, String code) {
        assertThat(FactusCodes.unitOfMeasure(unit)).isEqualTo(code);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"IVA, 01", "INC, 04"})
    @DisplayName("cada impuesto de línea tiene el código de su tributo")
    void taxTypes(TaxType type, String code) {
        assertThat(FactusCodes.tax(type)).isEqualTo(code);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"CASH, 10", "CHECK, 20", "BANK_DEPOSIT, 42", "BANK_TRANSFER, 47", "CREDIT_CARD, 48",
            "DEBIT_CARD, 49"})
    @DisplayName("cada medio de pago tiene su código de la DIAN")
    void paymentMethods(PaymentMethod method, String code) {
        assertThat(FactusCodes.paymentMethod(method)).isEqualTo(code);
    }

    @Test
    @DisplayName("contado es la forma de pago 1 y crédito la 2")
    void paymentForms() {
        assertThat(FactusCodes.paymentForm(PaymentTerms.cash())).isEqualTo("1");
        assertThat(FactusCodes.paymentForm(PaymentTerms.credit(LocalDate.of(2026, 11, 1)))).isEqualTo("2");
    }

    @Test
    @DisplayName("ninguna tabla asigna el mismo código a dos valores distintos")
    void codesAreUnique() {
        assertUnique(DocumentType.values(), FactusCodes::documentType);
        assertUnique(FiscalResponsibility.values(), FactusCodes::responsibility);
        assertUnique(UnitOfMeasure.values(), FactusCodes::unitOfMeasure);
        assertUnique(PaymentMethod.values(), FactusCodes::paymentMethod);
        assertUnique(TaxType.values(), FactusCodes::tax);
    }

    private static <E> void assertUnique(E[] values, Function<E, String> code) {
        assertThat(Arrays.stream(values).map(code).distinct().count()).isEqualTo(values.length);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"PARTIAL_RETURN, 1", "TOTAL_ANNULMENT, 2"})
    @DisplayName("cada motivo de nota crédito tiene su correction_concept_code (devolución parcial 1, anulación 2)")
    void creditNoteReasons(CreditNoteReason reason, String code) {
        assertThat(FactusCodes.correctionConcept(reason)).isEqualTo(code);
        assertThat(Arrays.stream(CreditNoteReason.values()).map(FactusCodes::correctionConcept).distinct())
                .hasSize(CreditNoteReason.values().length);
    }

    @Test
    @DisplayName("la nota referencia una factura electrónica: customization_id 20")
    void creditNoteCustomization() {
        assertThat(FactusCodes.CREDIT_NOTE_REFERENCING_INVOICE).isEqualTo("20");
    }
}
