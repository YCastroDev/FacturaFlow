package com.facturaflow.invoicing.domain.model;

import com.facturaflow.invoicing.domain.exception.InvalidElectronicDocumentException;
import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Resultado de la emisión ante la DIAN (vía Factus). {@code officialTotal} es el total
 * con validez legal; puede diferir del calculado por el dominio (ADR 0004, decisión B).
 * {@code validatedAt} es un instante: la zona horaria es asunto de presentación (ADR 0005).
 */
public record ElectronicDocument(String number, String cufe, URI pdfUrl, Instant validatedAt,
                                 Money officialTotal) {

    private static final Pattern CUFE = Pattern.compile("[0-9a-f]{96}");

    public ElectronicDocument {
        if (number == null || number.isBlank()) {
            throw InvalidElectronicDocumentException.missing("el número de factura");
        }
        number = number.strip();
        if (cufe == null || !CUFE.matcher(cufe.strip().toLowerCase(Locale.ROOT)).matches()) {
            throw InvalidElectronicDocumentException.invalidCufe(cufe);
        }
        cufe = cufe.strip().toLowerCase(Locale.ROOT);
        if (pdfUrl == null) {
            throw InvalidElectronicDocumentException.missing("la URL del PDF");
        }
        if (!pdfUrl.isAbsolute() || !("https".equalsIgnoreCase(pdfUrl.getScheme())
                || "http".equalsIgnoreCase(pdfUrl.getScheme()))) {
            throw InvalidElectronicDocumentException.invalidPdfUrl(pdfUrl.toString());
        }
        if (validatedAt == null) {
            throw InvalidElectronicDocumentException.missing("la fecha de validación");
        }
        if (officialTotal == null) {
            throw InvalidElectronicDocumentException.missing("el total oficial");
        }
    }
}
