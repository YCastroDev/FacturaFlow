package com.facturaflow.invoicing.domain.model;

import com.facturaflow.invoicing.domain.exception.InvalidCustomerException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Copia de los datos del cliente al momento de la venta. No es una referencia al
 * cliente de otro servicio: si el cliente cambia después, la factura no cambia.
 *
 * <p>Reglas (ADR 0007):
 * <ul>
 *   <li>una {@link PersonType#LEGAL_ENTITY persona jurídica} exige {@code companyName} y no admite {@code names};</li>
 *   <li>una {@link PersonType#NATURAL_PERSON persona natural} exige {@code names} y no admite {@code companyName};</li>
 *   <li>la situación tributaria ({@link FiscalProfile}) es obligatoria;</li>
 *   <li>email, municipio, dirección y teléfono son opcionales.</li>
 * </ul>
 * Los textos vacíos se normalizan a {@code null}.
 */
public record CustomerSnapshot(IdentificationDocument identification, PersonType personType, String companyName,
                               String names, FiscalProfile fiscalProfile, String email, Municipality municipality,
                               String address, PhoneNumber phone) {

    public static final int MAX_ADDRESS_LENGTH = 250;
    // Cuantificadores posesivos y etiquetas del dominio sin punto: tiempo lineal (sin backtracking) y sin
    // etiquetas vacías. Copia en catalog (CustomerProfile, ADR 0011).
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[^\\s@]++@[^\\s@.]++(?:\\.[^\\s@.]++)++");
    private static final int MAX_EMAIL_LENGTH = 254;

    public CustomerSnapshot {
        if (identification == null) {
            throw InvalidCustomerException.missingIdentification();
        }
        if (personType == null) {
            throw InvalidCustomerException.missingPersonType();
        }
        companyName = blankToNull(companyName);
        names = blankToNull(names);
        switch (personType) {
            case LEGAL_ENTITY -> {
                if (companyName == null) {
                    throw InvalidCustomerException.companyNameRequired();
                }
                if (names != null) {
                    throw InvalidCustomerException.namesNotAllowedForLegalEntity();
                }
            }
            case NATURAL_PERSON -> {
                if (names == null) {
                    throw InvalidCustomerException.namesRequired();
                }
                if (companyName != null) {
                    throw InvalidCustomerException.companyNameNotAllowedForNaturalPerson();
                }
            }
        }
        if (fiscalProfile == null) {
            throw InvalidCustomerException.missingFiscalProfile();
        }
        email = blankToNull(email);
        if (email != null && (email.length() > MAX_EMAIL_LENGTH || !EMAIL_PATTERN.matcher(email).matches())) {
            throw InvalidCustomerException.invalidEmail(email);
        }
        address = blankToNull(address);
        if (address != null && address.length() > MAX_ADDRESS_LENGTH) {
            throw InvalidCustomerException.addressTooLong(address.length(), MAX_ADDRESS_LENGTH);
        }
    }

    /** Persona jurídica, sin datos de ubicación (se agregan con {@link #withLocation}). */
    public static CustomerSnapshot company(IdentificationDocument identification, String companyName,
                                           FiscalProfile fiscalProfile, String email) {
        return new CustomerSnapshot(identification, PersonType.LEGAL_ENTITY, companyName, null, fiscalProfile, email,
                null, null, null);
    }

    /** Persona natural, sin datos de ubicación (se agregan con {@link #withLocation}). */
    public static CustomerSnapshot person(IdentificationDocument identification, String names,
                                          FiscalProfile fiscalProfile, String email) {
        return new CustomerSnapshot(identification, PersonType.NATURAL_PERSON, null, names, fiscalProfile, email,
                null, null, null);
    }

    /** Copia con municipio, dirección y teléfono (cada uno puede ser {@code null}). */
    public CustomerSnapshot withLocation(Municipality municipality, String address, PhoneNumber phone) {
        return new CustomerSnapshot(identification, personType, companyName, names, fiscalProfile, email,
                municipality, address, phone);
    }

    /** Razón social si es persona jurídica; si no, los nombres. */
    public String displayName() {
        return personType == PersonType.LEGAL_ENTITY ? companyName : names;
    }

    public Optional<String> optionalEmail() {
        return Optional.ofNullable(email);
    }

    public Optional<Municipality> optionalMunicipality() {
        return Optional.ofNullable(municipality);
    }

    public Optional<String> optionalAddress() {
        return Optional.ofNullable(address);
    }

    public Optional<PhoneNumber> optionalPhone() {
        return Optional.ofNullable(phone);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
