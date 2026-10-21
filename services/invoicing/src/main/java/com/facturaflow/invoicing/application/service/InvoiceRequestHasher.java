package com.facturaflow.invoicing.application.service;

import com.facturaflow.invoicing.application.port.out.RequestHash;
import com.facturaflow.invoicing.domain.model.CustomerSnapshot;
import com.facturaflow.invoicing.domain.model.IdentificationDocument;
import com.facturaflow.invoicing.domain.model.InvoiceLine;
import com.facturaflow.invoicing.domain.model.LineTax;
import com.facturaflow.invoicing.domain.model.PaymentTerms;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Calcula la huella de una petición de creación (ADR 0006, D1).
 *
 * <p>Criterio: dos peticiones son iguales si producirían la misma factura, salvo el id y la
 * fecha de emisión. Por eso la huella se calcula sobre los value objects ya construidos (la
 * normalización la define el dominio: escala 2, textos sin espacios, vacío = {@code null}) y
 * deja fuera el código de referencia (es la clave) y la fecha (la pone el servidor).
 *
 * <p>Las líneas conservan su orden (definen la numeración del documento); los impuestos de
 * cada línea se ordenan por tipo (su orden no significa nada).
 *
 * <p>Codificación canónica: cada valor con su longitud delante ({@code len:valor;}) y
 * {@code ~;} para {@code null}, así ninguna concatenación es ambigua. El prefijo de versión
 * permite cambiar el formato en el futuro sabiendo con cuál se calculó cada huella.
 */
final class InvoiceRequestHasher {

    static final String FORMAT_VERSION = "invoice-request-v1";

    private InvoiceRequestHasher() {
    }

    static RequestHash hash(InvoiceContent content) {
        return new RequestHash(HexFormat.of().formatHex(sha256(canonicalForm(content))));
    }

    static String canonicalForm(InvoiceContent content) {
        Canonical out = new Canonical();
        out.add(FORMAT_VERSION);

        CustomerSnapshot customer = content.customer();
        IdentificationDocument identification = customer.identification();
        out.add(identification.type().name());
        out.add(identification.number());
        out.add(identification.checkDigit() == null ? null : identification.checkDigit().toString());
        out.add(customer.companyName());
        out.add(customer.names());
        out.add(customer.email());

        out.add(content.paymentMethod().name());
        switch (content.paymentTerms()) {
            case PaymentTerms.Cash _ -> {
                out.add("CASH");
                out.add(null);
            }
            case PaymentTerms.Credit(var dueDate) -> {
                out.add("CREDIT");
                out.add(dueDate.toString());
            }
        }
        out.add(content.observation());

        out.add(Integer.toString(content.lines().size()));
        for (InvoiceLine line : content.lines()) {
            out.add(line.code());
            out.add(line.name());
            out.add(line.quantity().value().toPlainString());
            out.add(line.unitPrice().amount().toPlainString());
            out.add(line.discountRate().percent().toPlainString());
            out.add(line.unit().name());
            out.add(Boolean.toString(line.excluded()));
            List<LineTax> taxes = line.taxes().stream().sorted(Comparator.comparing(LineTax::type)).toList();
            out.add(Integer.toString(taxes.size()));
            for (LineTax tax : taxes) {
                out.add(tax.type().name());
                out.add(tax.rate().percent().toPlainString());
            }
        }
        return out.toString();
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("La JVM no ofrece SHA-256", e);   // obligatorio en toda JVM
        }
    }

    private static final class Canonical {

        private final StringBuilder text = new StringBuilder();

        void add(String value) {
            if (value == null) {
                text.append("~;");
            } else {
                text.append(value.length()).append(':').append(value).append(';');
            }
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }
}
