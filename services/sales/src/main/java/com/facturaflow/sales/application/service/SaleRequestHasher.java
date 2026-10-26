package com.facturaflow.sales.application.service;

import com.facturaflow.sales.application.port.out.RequestHash;
import com.facturaflow.sales.domain.model.PaymentTerms;

/**
 * Huella de una petición de venta (ADR 0012), con el mismo criterio que invoicing: dos peticiones son
 * iguales si piden lo mismo. Se calcula sobre la PETICIÓN normalizada (no sobre lo que devolvió catalog):
 * si un precio cambia en catalog entre la primera petición y su reintento, sigue siendo la misma venta
 * y el reintento devuelve la ya creada, con los precios de entonces.
 *
 * <p>Queda fuera el código de referencia (es la clave). Las líneas conservan su orden; los códigos de
 * producto van en minúsculas (catalog no distingue mayúsculas).
 */
final class SaleRequestHasher {

    static final String FORMAT_VERSION = "sale-request-v1";

    private SaleRequestHasher() {
    }

    static RequestHash hash(SaleRequest request) {
        return CanonicalText.sha256(canonicalForm(request));
    }

    static String canonicalForm(SaleRequest request) {
        CanonicalText out = new CanonicalText();
        out.add(FORMAT_VERSION);
        out.add(request.customer().type().name());
        out.add(request.customer().number());
        out.add(Integer.toString(request.lines().size()));
        for (SaleRequest.Line line : request.lines()) {
            out.add(line.key());
            out.add(line.quantity().value().toPlainString());
            out.add(line.discountRate().percent().toPlainString());
        }
        out.add(request.paymentMethod().name());
        switch (request.paymentTerms()) {
            case PaymentTerms.Cash ignored -> out.add("CASH");
            case PaymentTerms.Credit credit -> {
                out.add("CREDIT");
                out.add(credit.dueDate().toString());
            }
        }
        out.add(request.observation());
        return out.toString();
    }
}
