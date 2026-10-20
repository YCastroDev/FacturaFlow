package com.facturaflow.invoicing.domain.model;

import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.excludedLine;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.invoicing.domain.exception.InvalidInvoiceLineException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class InvoiceLineTest {

    @Test
    @DisplayName("el descuento se aplica antes del impuesto: el IVA se calcula sobre el valor descontado")
    void discountIsAppliedBeforeTax() {
        InvoiceLine line = line("2", "50000.00", "10", "19");

        assertThat(line.grossAmount()).isEqualTo(Money.of("100000.00"));
        assertThat(line.discountAmount()).isEqualTo(Money.of("10000.00"));
        assertThat(line.taxableAmount()).isEqualTo(Money.of("90000.00"));
        assertThat(line.taxAmount()).isEqualTo(Money.of("17100.00"));   // 19% de 90.000, no de 100.000
        assertThat(line.total()).isEqualTo(Money.of("107100.00"));
    }

    @Test
    @DisplayName("una línea excluida de IVA no genera impuesto")
    void excludedLineHasNoTax() {
        InvoiceLine line = excludedLine("3", "20000.00");

        assertThat(line.taxAmount()).isEqualTo(Money.ZERO);
        assertThat(line.total()).isEqualTo(Money.of("60000.00"));
    }

    @ParameterizedTest(name = "IVA {0}%")
    @ValueSource(strings = {"19", "0"})
    @DisplayName("una línea excluida de IVA no puede tener impuesto de tipo IVA, ni siquiera con tarifa 0")
    void rejectsExcludedLineWithIva(String ivaRate) {
        assertThatThrownBy(() -> new InvoiceLine("SKU-1", "Arroz", Quantity.of("1"), Money.of("1000"),
                DiscountRate.NONE, List.of(LineTax.of(TaxType.IVA, ivaRate)), UnitOfMeasure.KILOGRAM, true))
                .isInstanceOf(InvalidInvoiceLineException.class)
                .hasMessageContaining("excluida de IVA");
    }

    @Test
    @DisplayName("una línea excluida de IVA sí puede tener otros impuestos, como el INC")
    void excludedLineCanHaveOtherTaxes() {
        InvoiceLine line = new InvoiceLine("SKU-1", "Producto", Quantity.of("1"), Money.of("10000"),
                DiscountRate.NONE, List.of(LineTax.of(TaxType.INC, "8")), UnitOfMeasure.UNIT, true);

        assertThat(line.taxAmount()).isEqualTo(Money.of("800.00"));
        assertThat(line.taxAmount(TaxType.IVA)).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("una línea puede tener varios impuestos, todos calculados sobre el valor descontado")
    void lineWithSeveralTaxes() {
        InvoiceLine line = line("2", "50000.00", "10", LineTax.of(TaxType.IVA, "19"), LineTax.of(TaxType.INC, "8"));

        assertThat(line.taxAmount(TaxType.IVA)).isEqualTo(Money.of("17100.00"));   // 19% de 90.000
        assertThat(line.taxAmount(TaxType.INC)).isEqualTo(Money.of("7200.00"));    //  8% de 90.000
        assertThat(line.taxAmount()).isEqualTo(Money.of("24300.00"));
        assertThat(line.total()).isEqualTo(Money.of("114300.00"));
    }

    @Test
    @DisplayName("cada impuesto de la línea se redondea por separado antes de sumarse")
    void eachTaxIsRoundedSeparately() {
        // Base 0,06: IVA 19% = 0,0114 -> 0,01; INC 8% = 0,0048 -> 0,00. Suma: 0,01.
        // Si se sumaran las tarifas antes de redondear: 27% = 0,0162 -> 0,02.
        InvoiceLine line = line("1", "0.06", "0", LineTax.of(TaxType.IVA, "19"), LineTax.of(TaxType.INC, "8"));

        assertThat(line.taxAmount(TaxType.IVA)).isEqualTo(Money.of("0.01"));
        assertThat(line.taxAmount(TaxType.INC)).isEqualTo(Money.of("0.00"));
        assertThat(line.taxAmount()).isEqualTo(Money.of("0.01"));
    }

    @Test
    @DisplayName("una línea no puede repetir un tipo de impuesto")
    void rejectsDuplicateTaxType() {
        assertThatThrownBy(() -> line("1", "1000", "0", LineTax.of(TaxType.IVA, "19"), LineTax.of(TaxType.IVA, "5")))
                .isInstanceOf(InvalidInvoiceLineException.class)
                .hasMessageContaining("IVA más de una vez");
    }

    @Test
    @DisplayName("una línea sin impuestos es válida y no genera impuesto")
    void lineWithoutTaxes() {
        assertThat(line("1", "1000", "0").taxAmount()).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("la lista de impuestos es obligatoria e inmutable")
    void taxesAreRequiredAndImmutable() {
        assertThatThrownBy(() -> new InvoiceLine("SKU-1", "Producto", Quantity.of("1"), Money.of("1000"),
                DiscountRate.NONE, null, UnitOfMeasure.UNIT, false))
                .isInstanceOf(InvalidInvoiceLineException.class)
                .hasMessageContaining("impuestos");
        assertThatThrownBy(() -> line("1", "1000", "0", "19").taxes().add(LineTax.of(TaxType.INC, "8")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("un impuesto requiere tipo y tarifa")
    void lineTaxRequiresTypeAndRate() {
        assertThatThrownBy(() -> new LineTax(null, TaxRate.of("19"))).isInstanceOf(InvalidInvoiceLineException.class);
        assertThatThrownBy(() -> new LineTax(TaxType.IVA, null)).isInstanceOf(InvalidInvoiceLineException.class);
    }

    @Test
    @DisplayName("un descuento del 100% deja la línea en cero, sin impuesto")
    void fullDiscountLeavesZero() {
        InvoiceLine line = line("1", "5000.00", "100", "19");

        assertThat(line.taxableAmount()).isEqualTo(Money.ZERO);
        assertThat(line.total()).isEqualTo(Money.ZERO);
    }

    @ParameterizedTest(name = "{0} x {1} con {2}% dto. y {3}% imp. -> bruto {4}, dto. {5}, imp. {6}")
    @CsvSource({
            // cantidad, precio,  dto, imp, bruto,   descuento, impuesto
            "0.33,       0.33,    0,   0,   0.11,    0.00,      0.00",   // 0.1089 -> 0.11
            "0.50,       0.05,    0,   0,   0.03,    0.00,      0.00",   // 0.025 -> 0.03 (HALF_UP; HALF_EVEN daría 0.02)
            "1,          10.05,   10,  0,   10.05,   1.01,      0.00",   // descuento 1.005 -> 1.01
            "1,          0.10,    0,   5,   0.10,    0.00,      0.01",   // impuesto 0.005 -> 0.01
            "3,          1999.99, 5,   5,   5999.97, 300.00,    285.00"  // 299.9985 -> 300.00; 284.9985 -> 285.00
    })
    @DisplayName("cada valor de la línea se redondea HALF_UP a dos decimales")
    void roundsEachAmountOfTheLine(String quantity, String price, String discount, String tax,
                                   String gross, String discountAmount, String taxAmount) {
        InvoiceLine line = line(quantity, price, discount, tax);

        assertThat(line.grossAmount()).isEqualTo(Money.of(gross));
        assertThat(line.discountAmount()).isEqualTo(Money.of(discountAmount));
        assertThat(line.taxAmount()).isEqualTo(Money.of(taxAmount));
    }

    @Test
    @DisplayName("el precio unitario debe ser mayor que cero")
    void rejectsZeroUnitPrice() {
        assertThatThrownBy(() -> line("1", "0", "0", "19"))
                .isInstanceOf(InvalidInvoiceLineException.class)
                .hasMessageContaining("mayor que cero");
    }

    @ParameterizedTest(name = "código ''{0}''")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("una línea sin código se rechaza")
    void rejectsMissingCode(String code) {
        assertThatThrownBy(() -> new InvoiceLine(code, "Producto", Quantity.of("1"), Money.of("1000"),
                DiscountRate.NONE, List.of(), UnitOfMeasure.UNIT, false))
                .isInstanceOf(InvalidInvoiceLineException.class)
                .hasMessageContaining("código");
    }

    @Test
    @DisplayName("una línea sin nombre se rechaza")
    void rejectsMissingName() {
        assertThatThrownBy(() -> new InvoiceLine("SKU-1", " ", Quantity.of("1"), Money.of("1000"),
                DiscountRate.NONE, List.of(), UnitOfMeasure.UNIT, false))
                .isInstanceOf(InvalidInvoiceLineException.class)
                .hasMessageContaining("nombre");
    }

    @Test
    @DisplayName("una línea sin unidad de medida se rechaza")
    void rejectsMissingUnit() {
        assertThatThrownBy(() -> new InvoiceLine("SKU-1", "Producto", Quantity.of("1"), Money.of("1000"),
                DiscountRate.NONE, List.of(), null, false))
                .isInstanceOf(InvalidInvoiceLineException.class)
                .hasMessageContaining("unidad de medida");
    }
}
