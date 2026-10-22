package com.facturaflow.invoicing.infrastructure.adapter.out.provider.fake;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Arma un PDF 1.4 mínimo y válido (una página A4, texto en Helvetica) sin librerías. Las
 * posiciones de la tabla {@code xref} se calculan al escribir cada objeto, que es lo que exige el
 * formato. Solo admite texto ASCII imprimible, suficiente para marcar el documento como falso.
 */
final class FakePdf {

    private FakePdf() {
    }

    static byte[] render(String... lines) {
        StringBuilder text = new StringBuilder("BT /F1 18 Tf 72 770 Td 24 TL");
        for (String line : lines) {
            text.append(" (").append(escape(line)).append(") '");
        }
        text.append(" ET");
        String stream = text.toString();

        List<String> objects = List.of(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R "
                        + "/Resources << /Font << /F1 5 0 R >> >> >>",
                "<< /Length " + stream.length() + " >>\nstream\n" + stream + "\nendstream",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, "%PDF-1.4\n");
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(out.size());
            write(out, (i + 1) + " 0 obj\n" + objects.get(i) + "\nendobj\n");
        }
        int xref = out.size();
        StringBuilder trailer = new StringBuilder("xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f \n");
        offsets.forEach(offset -> trailer.append("%010d 00000 n \n".formatted(offset)));
        trailer.append("trailer\n<< /Size ").append(objects.size() + 1).append(" /Root 1 0 R >>\n")
                .append("startxref\n").append(xref).append("\n%%EOF\n");
        write(out, trailer.toString());
        return out.toByteArray();
    }

    private static String escape(String line) {
        if (!line.chars().allMatch(c -> c >= 0x20 && c < 0x7f)) {
            throw new IllegalArgumentException("El PDF falso solo admite texto ASCII imprimible: " + line);
        }
        return line.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
    }

    private static void write(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.US_ASCII));
    }
}
