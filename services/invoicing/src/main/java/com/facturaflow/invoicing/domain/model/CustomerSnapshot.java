package com.facturaflow.invoicing.domain.model;

import com.facturaflow.invoicing.domain.exception.InvalidCustomerException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Copia de los datos del cliente al momento de la venta. No es una referencia al
 * cliente de otro servicio: si el cliente cambia después, la factura no cambia.
 *
 * <p>{@code companyName} (empresa) y {@code names} (persona natural) son opcionales
 * por separado, pero al menos uno debe tener texto. {@code email} es opcional.
 * Los textos vacíos se normalizan a {@code null}.
 */
public record CustomerSnapshot(IdentificationDocument identification, String companyName, String names, String email) {

    private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");
    private static final int MAX_EMAIL_LENGTH = 254;

    public CustomerSnapshot {
        if (identification == null) {
            throw InvalidCustomerException.missingIdentification();
        }
        companyName = blankToNull(companyName);
        names = blankToNull(names);
        email = blankToNull(email);
        if (companyName == null && names == null) {
            throw InvalidCustomerException.missingName();
        }
        if (email != null && (email.length() > MAX_EMAIL_LENGTH || !EMAIL.matcher(email).matches())) {
            throw InvalidCustomerException.invalidEmail(email);
        }
    }

    public static CustomerSnapshot company(IdentificationDocument identification, String companyName, String email) {
        return new CustomerSnapshot(identification, companyName, null, email);
    }

    public static CustomerSnapshot person(IdentificationDocument identification, String names, String email) {
        return new CustomerSnapshot(identification, null, names, email);
    }

    /** Razón social si es empresa; si no, los nombres. */
    public String displayName() {
        return companyName != null ? companyName : names;
    }

    public Optional<String> optionalEmail() {
        return Optional.ofNullable(email);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
