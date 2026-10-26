package com.facturaflow.sales.domain.model;

import com.facturaflow.sales.domain.exception.InvalidCustomerSnapshotException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Copia de los datos del cliente tal como estaban en catalog al confirmar la venta (ADR 0003): si el
 * cliente cambia después, la venta no cambia. Tiene todo lo que invoicing pide para facturar, con los
 * mismos nombres, para que el evento SaleConfirmed no vuelva a consultar a catalog (ADR 0012, D3).
 *
 * <p>Solo se valida la ESTRUCTURA (lo obligatorio está presente y el nombre corresponde al tipo de
 * persona); las reglas fiscales no se copian por tercera vez (ADR 0012, D4).
 *
 * @param customerId id del cliente en catalog (trazabilidad)
 * @param checkDigit solo para NIT
 */
public record CustomerSnapshot(UUID customerId, DocumentType documentType, String documentNumber, Integer checkDigit,
                               PersonType personType, String companyName, String names, TaxScheme taxScheme,
                               Set<FiscalResponsibility> fiscalResponsibilities, String municipalityCode,
                               String address, String phone, String email) {

    public CustomerSnapshot {
        require(customerId, "su id en catalog");
        require(documentType, "el tipo de documento");
        require(blankToNull(documentNumber), "el número de documento");
        require(personType, "el tipo de persona");
        require(taxScheme, "la responsabilidad tributaria");
        if (fiscalResponsibilities == null || fiscalResponsibilities.isEmpty()
                || fiscalResponsibilities.stream().anyMatch(Objects::isNull)) {
            throw InvalidCustomerSnapshotException.missing("sus responsabilidades fiscales");
        }
        fiscalResponsibilities = Collections.unmodifiableSet(EnumSet.copyOf(fiscalResponsibilities));
        companyName = blankToNull(companyName);
        names = blankToNull(names);
        switch (personType) {
            case LEGAL_ENTITY -> require(companyName, "la razón social");
            case NATURAL_PERSON -> require(names, "los nombres");
        }
        municipalityCode = blankToNull(municipalityCode);
        address = blankToNull(address);
        phone = blankToNull(phone);
        email = blankToNull(email);
    }

    /** Razón social si es persona jurídica; si no, los nombres. */
    public String displayName() {
        return personType == PersonType.LEGAL_ENTITY ? companyName : names;
    }

    private static void require(Object value, String field) {
        if (value == null) {
            throw InvalidCustomerSnapshotException.missing(field);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
