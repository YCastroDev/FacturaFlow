package com.facturaflow.sales.infrastructure.adapter.out.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Lo que sales lee de la API de catalog (ADR 0011): solo los campos que usa. Los enums viajan como texto
 * y se traducen en {@link CatalogRestClient}; un valor desconocido es una falla del contrato, no del
 * usuario.
 */
final class CatalogDtos {

    private CatalogDtos() {
    }

    record Customer(UUID id, String documentType, String documentNumber, Integer checkDigit, String personType,
                    String companyName, String names, String taxScheme, List<String> fiscalResponsibilities,
                    String municipalityCode, String address, String phone, String email, boolean active) {
    }

    record Product(UUID id, String code, String name, BigDecimal unitPrice, String unit, boolean ivaExcluded,
                   List<Tax> taxes, boolean active) {
    }

    record Tax(String type, BigDecimal rate) {
    }

    record LookupRequest(List<String> codes) {
    }

    record LookupResponse(List<Product> items) {
    }
}
