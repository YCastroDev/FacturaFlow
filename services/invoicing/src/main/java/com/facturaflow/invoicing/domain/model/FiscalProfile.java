package com.facturaflow.invoicing.domain.model;

import com.facturaflow.invoicing.domain.exception.InvalidCustomerException;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Situación tributaria del cliente: su responsabilidad tributaria y sus responsabilidades
 * fiscales (ADR 0007). Las responsabilidades son un conjunto no vacío; repetir una no cambia
 * nada, y {@link FiscalResponsibility#NOT_RESPONSIBLE} va siempre sola.
 */
public record FiscalProfile(TaxScheme taxScheme, Set<FiscalResponsibility> responsibilities) {

    public FiscalProfile {
        if (taxScheme == null) {
            throw InvalidCustomerException.missingTaxScheme();
        }
        if (responsibilities == null || responsibilities.isEmpty()) {
            throw InvalidCustomerException.missingFiscalResponsibilities();
        }
        if (responsibilities.stream().anyMatch(Objects::isNull)) {
            throw InvalidCustomerException.missingFiscalResponsibilities();
        }
        if (responsibilities.contains(FiscalResponsibility.NOT_RESPONSIBLE) && responsibilities.size() > 1) {
            throw InvalidCustomerException.notResponsibleCombined();
        }
        responsibilities = Collections.unmodifiableSet(EnumSet.copyOf(responsibilities));
    }

    public static FiscalProfile of(TaxScheme taxScheme, FiscalResponsibility... responsibilities) {
        return new FiscalProfile(taxScheme, new HashSet<>(Arrays.asList(responsibilities)));
    }

    /** No responsable de IVA ni de otras obligaciones: el caso típico del consumidor final. */
    public static FiscalProfile notResponsible() {
        return of(TaxScheme.NOT_APPLICABLE, FiscalResponsibility.NOT_RESPONSIBLE);
    }
}
