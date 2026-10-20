package com.facturaflow.invoicing.domain.model;

import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.VALID_CUFE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.invoicing.domain.exception.InvalidElectronicDocumentException;
import java.net.URI;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ElectronicDocumentTest {

    private static final URI PDF = URI.create("https://sandbox.example/invoice.pdf");
    private static final OffsetDateTime VALIDATED_AT = OffsetDateTime.parse("2026-10-20T10:30:00-05:00");

    @Test
    @DisplayName("el CUFE se guarda normalizado en minúsculas")
    void normalizesCufe() {
        ElectronicDocument document = new ElectronicDocument("SETP990000001", VALID_CUFE.toUpperCase(), PDF,
                VALIDATED_AT, Money.of("100"));

        assertThat(document.cufe()).isEqualTo(VALID_CUFE);
    }

    @ParameterizedTest(name = "CUFE ''{0}''")
    @ValueSource(strings = {"", "abc123", "zz23456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
            + "0123456789abcdef0123456789abcdef"})
    @DisplayName("un CUFE que no es un SHA-384 en hexadecimal se rechaza")
    void rejectsInvalidCufe(String cufe) {
        assertThatThrownBy(() -> new ElectronicDocument("SETP990000001", cufe, PDF, VALIDATED_AT, Money.of("100")))
                .isInstanceOf(InvalidElectronicDocumentException.class)
                .hasMessageContaining("CUFE");
    }

    @ParameterizedTest(name = "URL ''{0}''")
    @ValueSource(strings = {"/relative/invoice.pdf", "ftp://sandbox.example/invoice.pdf"})
    @DisplayName("la URL del PDF debe ser absoluta y http(s)")
    void rejectsInvalidPdfUrl(String url) {
        assertThatThrownBy(() -> new ElectronicDocument("SETP990000001", VALID_CUFE, URI.create(url), VALIDATED_AT,
                Money.of("100")))
                .isInstanceOf(InvalidElectronicDocumentException.class)
                .hasMessageContaining("URL");
    }

    @Test
    @DisplayName("un documento electrónico sin número, fecha de validación o total oficial se rechaza")
    void rejectsMissingFields() {
        assertThatThrownBy(() -> new ElectronicDocument(" ", VALID_CUFE, PDF, VALIDATED_AT, Money.of("1")))
                .isInstanceOf(InvalidElectronicDocumentException.class);
        assertThatThrownBy(() -> new ElectronicDocument("SETP1", VALID_CUFE, PDF, null, Money.of("1")))
                .isInstanceOf(InvalidElectronicDocumentException.class);
        assertThatThrownBy(() -> new ElectronicDocument("SETP1", VALID_CUFE, PDF, VALIDATED_AT, null))
                .isInstanceOf(InvalidElectronicDocumentException.class);
    }
}
