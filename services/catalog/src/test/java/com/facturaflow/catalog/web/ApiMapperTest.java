package com.facturaflow.catalog.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.facturaflow.catalog.model.DocumentType;
import com.facturaflow.catalog.model.FiscalResponsibility;
import com.facturaflow.catalog.model.PersonType;
import com.facturaflow.catalog.model.TaxScheme;
import com.facturaflow.catalog.model.TaxType;
import com.facturaflow.catalog.model.UnitOfMeasure;
import com.facturaflow.catalog.web.dto.ApiDocumentType;
import com.facturaflow.catalog.web.dto.ApiFiscalResponsibility;
import com.facturaflow.catalog.web.dto.ApiPersonType;
import com.facturaflow.catalog.web.dto.ApiTaxScheme;
import com.facturaflow.catalog.web.dto.ApiTaxType;
import com.facturaflow.catalog.web.dto.ApiUnitOfMeasure;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ApiMapperTest {

    static Stream<Arguments> enumPairs() {
        return Stream.of(
                Arguments.of(ApiUnitOfMeasure.class, UnitOfMeasure.class),
                Arguments.of(ApiTaxType.class, TaxType.class),
                Arguments.of(ApiDocumentType.class, DocumentType.class),
                Arguments.of(ApiPersonType.class, PersonType.class),
                Arguments.of(ApiTaxScheme.class, TaxScheme.class),
                Arguments.of(ApiFiscalResponsibility.class, FiscalResponsibility.class));
    }

    @ParameterizedTest(name = "{0} ↔ {1}")
    @MethodSource("enumPairs")
    @DisplayName("cada enum de la API tiene exactamente los mismos valores que su par del modelo")
    <A extends Enum<A>, M extends Enum<M>> void enumsMatchByName(Class<A> api, Class<M> model) {
        assertThat(names(api)).containsExactlyInAnyOrder(names(model));
        for (A value : api.getEnumConstants()) {
            assertThat(ApiMapper.convert(ApiMapper.convert(value, model), api)).isEqualTo(value);
        }
    }

    private static String[] names(Class<? extends Enum<?>> type) {
        return Arrays.stream(type.getEnumConstants()).map(Enum::name).toArray(String[]::new);
    }
}
