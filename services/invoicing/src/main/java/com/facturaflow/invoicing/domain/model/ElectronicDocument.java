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
 *
 * <p>Enlaces (ADR 0008):
 * <ul>
 *   <li>{@code qrUrl}: consulta del documento en el catálogo de la DIAN; es lo que codifica el QR
 *       de la representación gráfica;</li>
 *   <li>{@code publicUrl}: vista pública del documento en el proveedor. Es un enlace-capacidad
 *       (quien lo tenga ve la factura), así que nunca va a los logs.</li>
 * </ul>
 * El PDF no es un enlace: se pide al proveedor cuando se necesita.
 */
public record ElectronicDocument(String number, String cufe, URI qrUrl, URI publicUrl, Instant validatedAt,
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
        requireHttpUrl(qrUrl, "la URL de consulta en la DIAN (QR)");
        requireHttpUrl(publicUrl, "la URL pública del documento");
        if (validatedAt == null) {
            throw InvalidElectronicDocumentException.missing("la fecha de validación");
        }
        if (officialTotal == null) {
            throw InvalidElectronicDocumentException.missing("el total oficial");
        }
    }

    private static void requireHttpUrl(URI url, String description) {
        if (url == null) {
            throw InvalidElectronicDocumentException.missing(description);
        }
        if (!url.isAbsolute() || !("https".equalsIgnoreCase(url.getScheme())
                || "http".equalsIgnoreCase(url.getScheme()))) {
            throw InvalidElectronicDocumentException.invalidUrl(description, url.toString());
        }
    }
}
