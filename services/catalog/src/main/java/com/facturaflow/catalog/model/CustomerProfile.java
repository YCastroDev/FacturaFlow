package com.facturaflow.catalog.model;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Los datos editables de un cliente, validados (todo menos el documento, que es su identidad).
 *
 * <p>COPIA DELIBERADA de las reglas de {@code CustomerSnapshot}, {@code FiscalProfile},
 * {@code Municipality} y {@code PhoneNumber} de invoicing (ADR 0001, ADR 0011), para que un cliente del
 * catálogo siempre se pueda facturar:
 * <ul>
 *   <li>persona jurídica → solo {@code companyName}; persona natural → solo {@code names};</li>
 *   <li>tributo obligatorio y al menos una responsabilidad fiscal; {@code NOT_RESPONSIBLE} va sola;</li>
 *   <li>municipio DIVIPOLA de 5 dígitos con departamento existente (el catálogo completo de municipios
 *       lo valida el proveedor, no aquí);</li>
 *   <li>teléfono de 7 a 15 dígitos con '+' opcional (se quitan espacios, guiones, puntos y paréntesis);</li>
 *   <li>email con forma de email, de hasta 254 caracteres; dirección de hasta 250.</li>
 * </ul>
 * Municipio, dirección, teléfono y email son opcionales; los textos vacíos se normalizan a {@code null}.
 */
public record CustomerProfile(PersonType personType, String companyName, String names, TaxScheme taxScheme,
                              Set<FiscalResponsibility> fiscalResponsibilities, String municipalityCode,
                              String address, String phone, String email) {

    public static final int MAX_ADDRESS_LENGTH = 250;
    public static final int MAX_EMAIL_LENGTH = 254;

    private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");
    private static final Pattern MUNICIPALITY = Pattern.compile("[0-9]{5}");
    private static final Pattern PHONE = Pattern.compile("\\+?[0-9]{7,15}");
    private static final Pattern PHONE_SEPARATORS = Pattern.compile("[\\s().-]");
    /** Códigos de los 32 departamentos y de Bogotá D.C. (DIVIPOLA). */
    private static final Set<String> DEPARTMENTS = Set.of(
            "05", "08", "11", "13", "15", "17", "18", "19", "20", "23", "25", "27", "41", "44", "47", "50", "52",
            "54", "63", "66", "68", "70", "73", "76", "81", "85", "86", "88", "91", "94", "95", "97", "99");

    public CustomerProfile {
        if (personType == null) {
            throw CatalogRuleException.missing("el tipo de persona");
        }
        companyName = Texts.blankToNull(companyName);
        names = Texts.blankToNull(names);
        switch (personType) {
            case LEGAL_ENTITY -> {
                if (companyName == null) {
                    throw new CatalogRuleException("Una persona jurídica necesita la razón social (companyName).");
                }
                if (names != null) {
                    throw new CatalogRuleException("Una persona jurídica se identifica por su razón social: no "
                            + "envíe names.");
                }
            }
            case NATURAL_PERSON -> {
                if (names == null) {
                    throw new CatalogRuleException("Una persona natural necesita sus nombres (names).");
                }
                if (companyName != null) {
                    throw new CatalogRuleException("Una persona natural se identifica por sus nombres: no envíe "
                            + "companyName.");
                }
            }
        }
        if (taxScheme == null) {
            throw CatalogRuleException.missing("la responsabilidad tributaria (taxScheme)");
        }
        fiscalResponsibilities = validateResponsibilities(fiscalResponsibilities);
        municipalityCode = validateMunicipality(Texts.blankToNull(municipalityCode));
        address = Texts.maxLength(Texts.blankToNull(address), "dirección", MAX_ADDRESS_LENGTH);
        phone = normalizePhone(Texts.blankToNull(phone));
        email = validateEmail(Texts.blankToNull(email));
    }

    /** Razón social si es persona jurídica; si no, los nombres. */
    public String displayName() {
        return personType == PersonType.LEGAL_ENTITY ? companyName : names;
    }

    private static Set<FiscalResponsibility> validateResponsibilities(Set<FiscalResponsibility> responsibilities) {
        if (responsibilities == null || responsibilities.isEmpty()
                // anyMatch y no contains(null): los conjuntos inmutables (Set.of) lanzan NPE con contains(null).
                || responsibilities.stream().anyMatch(Objects::isNull)) {
            throw CatalogRuleException.missing("al menos una responsabilidad fiscal (NOT_RESPONSIBLE si no tiene "
                    + "ninguna)");
        }
        if (responsibilities.contains(FiscalResponsibility.NOT_RESPONSIBLE) && responsibilities.size() > 1) {
            throw new CatalogRuleException("NOT_RESPONSIBLE significa \"ninguna de las anteriores\": no se combina "
                    + "con otras responsabilidades fiscales.");
        }
        return Collections.unmodifiableSet(EnumSet.copyOf(responsibilities));
    }

    private static String validateMunicipality(String code) {
        if (code == null) {
            return null;
        }
        if (!MUNICIPALITY.matcher(code).matches()) {
            throw new CatalogRuleException("El código de municipio debe ser el DIVIPOLA de 5 dígitos (p. ej. 11001 "
                    + "para Bogotá).");
        }
        if (!DEPARTMENTS.contains(code.substring(0, 2))) {
            throw new CatalogRuleException("El municipio " + code + " no corresponde a ningún departamento "
                    + "(sus 2 primeros dígitos).");
        }
        return code;
    }

    private static String normalizePhone(String phone) {
        if (phone == null) {
            return null;
        }
        String normalized = PHONE_SEPARATORS.matcher(phone).replaceAll("");
        if (!PHONE.matcher(normalized).matches()) {
            throw new CatalogRuleException("El teléfono debe tener de 7 a 15 dígitos, con '+' inicial opcional.");
        }
        return normalized;
    }

    private static String validateEmail(String email) {
        if (email != null && (email.length() > MAX_EMAIL_LENGTH || !EMAIL.matcher(email).matches())) {
            throw new CatalogRuleException("El email no tiene un formato válido.");
        }
        return email;
    }
}
