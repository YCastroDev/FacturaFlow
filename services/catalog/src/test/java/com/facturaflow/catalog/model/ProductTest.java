package com.facturaflow.catalog.model;

import static com.facturaflow.catalog.model.CatalogFixtures.LATER;
import static com.facturaflow.catalog.model.CatalogFixtures.NOW;
import static com.facturaflow.catalog.model.CatalogFixtures.coffee;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProductTest {

    private static ProductDetails details(String price, boolean ivaExcluded, ProductTax... taxes) {
        return new ProductDetails("Producto", new BigDecimal(price), UnitOfMeasure.KILOGRAM, ivaExcluded,
                List.of(taxes));
    }

    @Test
    @DisplayName("un producto nuevo nace activo, con el código y el nombre sin espacios a los lados")
    void newProductIsActive() {
        Product product = new Product("  CAFE-500 ", new ProductDetails("  Café  ", new BigDecimal("18500"),
                UnitOfMeasure.UNIT, false, List.of()), NOW);

        assertThat(product.code()).isEqualTo("CAFE-500");
        assertThat(product.details().name()).isEqualTo("Café");
        assertThat(product.active()).isTrue();
        assertThat(product.createdAt()).isEqualTo(NOW);
        assertThat(product.updatedAt()).isEqualTo(NOW);
        assertThat(product.version()).as("la asigna JPA al guardarlo").isNull();
    }

    @Test
    @DisplayName("el precio se guarda con 2 decimales")
    void priceHasTwoDecimals() {
        assertThat(details("18500", false).unitPrice()).isEqualTo(new BigDecimal("18500.00"));
        assertThat(details("0.5", false).unitPrice()).isEqualTo(new BigDecimal("0.50"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"0", "-1", "0.00"})
    @DisplayName("el precio debe ser mayor que cero")
    void priceMustBePositive(String price) {
        assertThatThrownBy(() -> details(price, false))
                .isInstanceOf(CatalogRuleException.class).hasMessageContaining("mayor que cero");
    }

    @Test
    @DisplayName("un precio con más de 2 decimales se rechaza: un dato maestro no se redondea")
    void priceWithMoreThanTwoDecimalsIsRejected() {
        assertThatThrownBy(() -> details("18500.005", false))
                .isInstanceOf(CatalogRuleException.class).hasMessageContaining("2 decimales");
        assertThat(details("18500.500", false).unitPrice()).as("los ceros a la derecha no cuentan")
                .isEqualTo(new BigDecimal("18500.50"));
    }

    @Test
    @DisplayName("el precio admite hasta 13 dígitos enteros (NUMERIC(15,2))")
    void priceHasAnUpperBound() {
        assertThat(details("9999999999999.99", false).unitPrice()).isEqualByComparingTo("9999999999999.99");
        assertThatThrownBy(() -> details("10000000000000", false)).isInstanceOf(CatalogRuleException.class);
    }

    @Test
    @DisplayName("los impuestos quedan ordenados por tipo y con la tarifa a 2 decimales")
    void taxesAreSortedAndNormalized() {
        ProductDetails details = details("1000", false, ProductTax.of(TaxType.INC, "8"), ProductTax.of(TaxType.IVA, "19"));

        assertThat(details.taxes()).extracting(ProductTax::type).containsExactly(TaxType.IVA, TaxType.INC);
        assertThat(details.taxes().getFirst().rate()).isEqualTo(new BigDecimal("19.00"));
    }

    @Test
    @DisplayName("un tipo de impuesto no se repite")
    void taxTypesAreNotRepeated() {
        assertThatThrownBy(() -> details("1000", false, ProductTax.of(TaxType.IVA, "19"), ProductTax.of(TaxType.IVA, "5")))
                .isInstanceOf(CatalogRuleException.class).hasMessageContaining("repetido");
    }

    @Test
    @DisplayName("un producto excluido de IVA no admite IVA, pero sí INC")
    void excludedProductHasNoIva() {
        assertThatThrownBy(() -> details("1000", true, ProductTax.of(TaxType.IVA, "0")))
                .isInstanceOf(CatalogRuleException.class).hasMessageContaining("excluido de IVA");
        assertThat(details("1000", true, ProductTax.of(TaxType.INC, "8")).taxes()).hasSize(1);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"-1", "100.01", "19.005"})
    @DisplayName("la tarifa va de 0 a 100 con máximo 2 decimales")
    void taxRateRange(String rate) {
        assertThatThrownBy(() -> ProductTax.of(TaxType.IVA, rate)).isInstanceOf(CatalogRuleException.class);
    }

    @Test
    @DisplayName("el código y el nombre son obligatorios y tienen un largo máximo")
    void codeAndNameLimits() {
        assertThatThrownBy(() -> new Product(" ", coffee(), NOW)).isInstanceOf(CatalogRuleException.class);
        assertThatThrownBy(() -> new Product("X".repeat(51), coffee(), NOW))
                .isInstanceOf(CatalogRuleException.class).hasMessageContaining("50");
        assertThatThrownBy(() -> new ProductDetails("N".repeat(301), BigDecimal.TEN, UnitOfMeasure.UNIT, false, List.of()))
                .isInstanceOf(CatalogRuleException.class).hasMessageContaining("300");
        assertThatThrownBy(() -> new ProductDetails("Producto", BigDecimal.TEN, UnitOfMeasure.UNIT, false, null))
                .isInstanceOf(CatalogRuleException.class).hasMessageContaining("[]");
    }

    @Test
    @DisplayName("actualizar reemplaza los datos y la fecha de actualización")
    void updateReplacesDetails() {
        Product product = new Product("CAFE-500", coffee(), NOW);

        boolean changed = product.update("CAFE-500", coffee("19900"), LATER);

        assertThat(changed).isTrue();
        assertThat(product.details().unitPrice()).isEqualTo(new BigDecimal("19900.00"));
        assertThat(product.updatedAt()).isEqualTo(LATER);
        assertThat(product.createdAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("actualizar con los mismos datos no cambia nada (ni la fecha)")
    void identicalUpdateChangesNothing() {
        Product product = new Product("CAFE-500", coffee(), NOW);

        assertThat(product.update(" CAFE-500 ", coffee("18500.0"), LATER)).isFalse();
        assertThat(product.updatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("el código no se puede cambiar, ni siquiera en mayúsculas")
    void codeIsImmutable() {
        Product product = new Product("CAFE-500", coffee(), NOW);

        assertThatThrownBy(() -> product.update("cafe-500", coffee("19900"), LATER))
                .isInstanceOf(CatalogRuleException.class).hasMessageContaining("No se puede cambiar el código");
        assertThat(product.details()).isEqualTo(coffee());
    }

    @Test
    @DisplayName("desactivar y reactivar son idempotentes")
    void deactivateAndActivateAreIdempotent() {
        Product product = new Product("CAFE-500", coffee(), NOW);

        assertThat(product.deactivate(LATER)).isTrue();
        assertThat(product.active()).isFalse();
        assertThat(product.deactivate(LATER.plusSeconds(1))).isFalse();
        assertThat(product.updatedAt()).isEqualTo(LATER);
        assertThat(product.activate(LATER)).isTrue();
        assertThat(product.activate(LATER)).isFalse();
        assertThat(product.active()).isTrue();
    }
}
