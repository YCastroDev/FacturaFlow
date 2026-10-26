package com.facturaflow.sales.domain.model;

import com.facturaflow.sales.domain.exception.InvalidSaleException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * El documento con el que la petición identifica al cliente (tipo + número, sin DV: lo aporta catalog).
 * El número se normaliza como en catalog (sin espacios, en mayúsculas) y solo se exige que sea de 3 a
 * 20 letras o dígitos: el formato exacto por tipo lo valida catalog, dueño del cliente.
 */
public record CustomerDocumentRef(DocumentType type, String number) {

    private static final Pattern FORMAT = Pattern.compile("[A-Z0-9]{3,20}");

    public CustomerDocumentRef {
        if (type == null) {
            throw InvalidSaleException.missing("el tipo de documento del cliente");
        }
        number = number == null ? null : number.strip().toUpperCase(Locale.ROOT);
        if (number == null || !FORMAT.matcher(number).matches()) {
            throw InvalidSaleException.invalidCustomerDocument(number);
        }
    }
}
