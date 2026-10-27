package com.facturaflow.invoicing.application.port.out;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Representación gráfica (PDF) de un documento emitido (factura o nota crédito), tal como la
 * entrega el proveedor.
 * {@code fileName} se normaliza a caracteres seguros para un header HTTP y termina en {@code .pdf}.
 * El arreglo no se copia: quien lo recibe no debe modificarlo. La igualdad compara el contenido
 * (no la referencia del arreglo) y {@code toString} solo muestra su tamaño.
 */
public record InvoicePdf(String fileName, byte[] content) {

    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9._-]");
    private static final String EXTENSION = ".pdf";

    public InvoicePdf {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("El PDF requiere un nombre de archivo.");
        }
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("El PDF no puede estar vacío.");
        }
        fileName = UNSAFE.matcher(fileName.strip()).replaceAll("_");
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(EXTENSION)) {
            fileName = fileName + EXTENSION;
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof InvoicePdf that && fileName.equals(that.fileName)
                && Arrays.equals(content, that.content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fileName, Arrays.hashCode(content));
    }

    @Override
    public String toString() {
        return "InvoicePdf[fileName=" + fileName + ", content=" + content.length + " bytes]";
    }
}
