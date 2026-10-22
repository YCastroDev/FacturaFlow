package com.facturaflow.invoicing.domain.model;

import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.VALID_CUFE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.invoicing.domain.exception.InvalidElectronicDocumentException;
import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ElectronicDocumentTest {

    private static final URI QR = URI.create("https://dian.example/searchqr?documentkey=abc");
    private static final URI PUBLIC = URI.create("https://sandbox.example/documents/bills/abc");
    private static final Instant VALIDATED_AT = Instant.parse("2026-10-20T15:30:00Z");

    @Test
    @DisplayName("el CUFE se guarda normalizado en minúsculas")
    void normalizesCufe() {
        ElectronicDocument document = new ElectronicDocument("SETP990000001", VALID_CUFE.toUpperCase(), QR, PUBLIC,
                VALIDATED_AT, Money.of("100"));

        assertThat(document.cufe()).isEqualTo(VALID_CUFE);
    }

    @ParameterizedTest(name = "CUFE ''{0}''")
    @ValueSource(strings = {"", "abc123", "zz23456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
            + "0123456789abcdef0123456789abcdef"})
    @DisplayName("un CUFE que no es un SHA-384 en hexadecimal se rechaza")
    void rejectsInvalidCufe(String cufe) {
        assertThatThrownBy(() -> new ElectronicDocument("SETP990000001", cufe, QR, PUBLIC, VALIDATED_AT,
                Money.of("100")))
                .isInstanceOf(InvalidElectronicDocumentException.class)
                .hasMessageContaining("CUFE");
    }

    @ParameterizedTest(name = "URL ''{0}''")
    @ValueSource(strings = {"/relative/qr", "ftp://dian.example/qr"})
    @DisplayName("la URL de consulta en la DIAN debe ser absoluta y http(s)")
    void rejectsInvalidQrUrl(String url) {
        assertThatThrownBy(() -> new ElectronicDocument("SETP990000001", VALID_CUFE, URI.create(url), PUBLIC,
                VALIDATED_AT, Money.of("100")))
                .isInstanceOf(InvalidElectronicDocumentException.class)
                .hasMessageContaining("QR");
    }

    @ParameterizedTest(name = "URL ''{0}''")
    @ValueSource(strings = {"/relative/document", "ftp://sandbox.example/document"})
    @DisplayName("la URL pública del documento debe ser absoluta y http(s)")
    void rejectsInvalidPublicUrl(String url) {
        assertThatThrownBy(() -> new ElectronicDocument("SETP990000001", VALID_CUFE, QR, URI.create(url),
                VALIDATED_AT, Money.of("100")))
                .isInstanceOf(InvalidElectronicDocumentException.class)
                .hasMessageContaining("URL pública");
    }

    @Test
    @DisplayName("un documento electrónico sin número, enlaces, fecha de validación o total oficial se rechaza")
    void rejectsMissingFields() {
        assertThatThrownBy(() -> new ElectronicDocument(" ", VALID_CUFE, QR, PUBLIC, VALIDATED_AT, Money.of("1")))
                .isInstanceOf(InvalidElectronicDocumentException.class);
        assertThatThrownBy(() -> new ElectronicDocument("SETP1", VALID_CUFE, null, PUBLIC, VALIDATED_AT,
                Money.of("1")))
                .isInstanceOf(InvalidElectronicDocumentException.class);
        assertThatThrownBy(() -> new ElectronicDocument("SETP1", VALID_CUFE, QR, null, VALIDATED_AT, Money.of("1")))
                .isInstanceOf(InvalidElectronicDocumentException.class);
        assertThatThrownBy(() -> new ElectronicDocument("SETP1", VALID_CUFE, QR, PUBLIC, null, Money.of("1")))
                .isInstanceOf(InvalidElectronicDocumentException.class);
        assertThatThrownBy(() -> new ElectronicDocument("SETP1", VALID_CUFE, QR, PUBLIC, VALIDATED_AT, null))
                .isInstanceOf(InvalidElectronicDocumentException.class);
    }
}
