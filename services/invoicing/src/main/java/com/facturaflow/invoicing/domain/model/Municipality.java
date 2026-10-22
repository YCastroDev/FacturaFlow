package com.facturaflow.invoicing.domain.model;

import com.facturaflow.invoicing.domain.exception.InvalidMunicipalityException;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Municipio identificado por su código DIVIPOLA del DANE: 5 dígitos, los 2 primeros son el
 * departamento (11001 = Bogotá, 05001 = Medellín). Es un código oficial del Estado, no de un
 * proveedor (ADR 0007). Se valida el formato y el departamento; el catálogo completo de
 * municipios no vive en el dominio.
 */
public record Municipality(String code) {

    private static final Pattern FORMAT = Pattern.compile("[0-9]{5}");

    /** Códigos de los 32 departamentos y de Bogotá D.C. (DIVIPOLA). */
    private static final Set<String> DEPARTMENTS = Set.of(
            "05", "08", "11", "13", "15", "17", "18", "19", "20", "23", "25", "27", "41", "44", "47", "50", "52",
            "54", "63", "66", "68", "70", "73", "76", "81", "85", "86", "88", "91", "94", "95", "97", "99");

    public Municipality {
        code = code == null ? null : code.strip();
        if (code == null || !FORMAT.matcher(code).matches()) {
            throw InvalidMunicipalityException.invalidFormat(code);
        }
        if (!DEPARTMENTS.contains(code.substring(0, 2))) {
            throw InvalidMunicipalityException.unknownDepartment(code);
        }
    }

    public static Municipality of(String code) {
        return new Municipality(code);
    }

    public String departmentCode() {
        return code.substring(0, 2);
    }
}
