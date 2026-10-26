package com.facturaflow.sales.infrastructure.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.facturaflow.sales.domain.model.DocumentType;
import com.facturaflow.sales.domain.model.FiscalResponsibility;
import com.facturaflow.sales.domain.model.PaymentMethod;
import com.facturaflow.sales.domain.model.PersonType;
import com.facturaflow.sales.domain.model.SaleStatus;
import com.facturaflow.sales.domain.model.TaxScheme;
import com.facturaflow.sales.domain.model.TaxType;
import com.facturaflow.sales.domain.model.UnitOfMeasure;
import com.facturaflow.sales.infrastructure.adapter.in.rest.dto.ApiDocumentType;
import com.facturaflow.sales.infrastructure.adapter.in.rest.dto.ApiFiscalResponsibility;
import com.facturaflow.sales.infrastructure.adapter.in.rest.dto.ApiPaymentMethod;
import com.facturaflow.sales.infrastructure.adapter.in.rest.dto.ApiPersonType;
import com.facturaflow.sales.infrastructure.adapter.in.rest.dto.ApiSaleStatus;
import com.facturaflow.sales.infrastructure.adapter.in.rest.dto.ApiTaxScheme;
import com.facturaflow.sales.infrastructure.adapter.in.rest.dto.ApiTaxType;
import com.facturaflow.sales.infrastructure.adapter.in.rest.dto.ApiUnitOfMeasure;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ApiMapperTest {

    static Stream<Arguments> enumPairs() {
        return Stream.of(
                Arguments.of(ApiDocumentType.class, DocumentType.class),
                Arguments.of(ApiPaymentMethod.class, PaymentMethod.class),
                Arguments.of(ApiSaleStatus.class, SaleStatus.class),
                Arguments.of(ApiUnitOfMeasure.class, UnitOfMeasure.class),
                Arguments.of(ApiTaxType.class, TaxType.class),
                Arguments.of(ApiPersonType.class, PersonType.class),
                Arguments.of(ApiTaxScheme.class, TaxScheme.class),
                Arguments.of(ApiFiscalResponsibility.class, FiscalResponsibility.class));
    }

    @ParameterizedTest(name = "{0} ↔ {1}")
    @MethodSource("enumPairs")
    @DisplayName("cada enum de la API tiene exactamente los mismos valores que su par del dominio")
    <A extends Enum<A>, M extends Enum<M>> void enumsMatchByName(Class<A> api, Class<M> domain) {
        assertThat(names(api)).containsExactlyInAnyOrder(names(domain));
        for (A value : api.getEnumConstants()) {
            assertThat(ApiMapper.convert(ApiMapper.convert(value, domain), api)).isEqualTo(value);
        }
    }

    private static String[] names(Class<? extends Enum<?>> type) {
        return Arrays.stream(type.getEnumConstants()).map(Enum::name).toArray(String[]::new);
    }
}
