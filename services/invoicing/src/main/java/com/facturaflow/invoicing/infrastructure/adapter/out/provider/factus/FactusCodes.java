package com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus;

import com.facturaflow.invoicing.domain.model.DocumentType;
import com.facturaflow.invoicing.domain.model.FiscalResponsibility;
import com.facturaflow.invoicing.domain.model.PaymentMethod;
import com.facturaflow.invoicing.domain.model.PaymentTerms;
import com.facturaflow.invoicing.domain.model.PersonType;
import com.facturaflow.invoicing.domain.model.TaxScheme;
import com.facturaflow.invoicing.domain.model.TaxType;
import com.facturaflow.invoicing.domain.model.UnitOfMeasure;

/**
 * Tablas de equivalencia dominio → códigos de Factus/DIAN (ADR 0004, decisión A). Cada código se
 * verificó contra el sandbox en el spike 0001 (sección 9). Los {@code switch} son exhaustivos: si
 * el dominio agrega un valor, esto deja de compilar hasta que se le asigne su código.
 */
final class FactusCodes {

    /** "Estándar de adopción del contribuyente": el código del ítem es el propio de la pyme. */
    static final String STANDARD_CODE_OWN = "999";
    /** Tributo IVA, que también marca una línea excluida (tarifa 0 con {@code is_excluded}). */
    static final String IVA = "01";

    private FactusCodes() {
    }

    static String documentType(DocumentType type) {
        return switch (type) {
            case CIVIL_REGISTRY -> "11";
            case IDENTITY_CARD -> "12";
            case CITIZENSHIP_CARD -> "13";
            case FOREIGNER_CARD -> "21";
            case FOREIGNER_ID_CARD -> "22";
            case NIT -> "31";
            case PASSPORT -> "41";
            case FOREIGN_ID_DOCUMENT -> "42";
            case SPECIAL_STAY_PERMIT -> "47";
            case TEMPORARY_PROTECTION_PERMIT -> "48";
            case FOREIGN_NIT -> "50";
        };
    }

    static String legalOrganization(PersonType type) {
        return switch (type) {
            case LEGAL_ENTITY -> "1";
            case NATURAL_PERSON -> "2";
        };
    }

    static String tribute(TaxScheme scheme) {
        return switch (scheme) {
            case IVA -> IVA;
            case NOT_APPLICABLE -> "ZZ";
        };
    }

    static String responsibility(FiscalResponsibility responsibility) {
        return switch (responsibility) {
            case LARGE_TAXPAYER -> "O-13";
            case SELF_WITHHOLDER -> "O-15";
            case VAT_WITHHOLDING_AGENT -> "O-23";
            case SIMPLE_TAX_REGIME -> "O-47";
            case NOT_RESPONSIBLE -> "R-99-PN";
        };
    }

    static String unitOfMeasure(UnitOfMeasure unit) {
        return switch (unit) {
            case UNIT -> "94";
            case KILOGRAM -> "KGM";
            case GRAM -> "GRM";
            case POUND -> "LBR";
            case LITER -> "LTR";
            case MILLILITER -> "MLT";
            case GALLON -> "GLL";
            case METER -> "MTR";
        };
    }

    static String tax(TaxType type) {
        return switch (type) {
            case IVA -> IVA;
            case INC -> "04";
        };
    }

    static String paymentMethod(PaymentMethod method) {
        return switch (method) {
            case CASH -> "10";
            case CHECK -> "20";
            case BANK_DEPOSIT -> "42";
            case BANK_TRANSFER -> "47";
            case CREDIT_CARD -> "48";
            case DEBIT_CARD -> "49";
        };
    }

    static String paymentForm(PaymentTerms terms) {
        return switch (terms) {
            case PaymentTerms.Cash _ -> "1";
            case PaymentTerms.Credit _ -> "2";
        };
    }
}
