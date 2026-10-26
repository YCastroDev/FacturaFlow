package com.facturaflow.invoicing.infrastructure.adapter.in.messaging;

import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.domain.model.Money;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Compara los totales INFORMATIVOS del evento (los de la copia del cálculo en sales) con los de la factura (ADR
 * 0013, decisión 7). Invoicing es la autoridad: la factura usa SIEMPRE los suyos. Una diferencia significa que
 * las dos copias del cálculo divergieron (ADR 0012, D7): se registra un WARN con ambos valores para corregirlo.
 */
final class SaleTotalsCheck {

    private static final Logger LOG = LoggerFactory.getLogger(SaleTotalsCheck.class);

    /** @return {@code true} si coinciden o el evento no trae totales (nada que comparar) */
    boolean matches(SaleConfirmedMessage.Totals sales, Invoice invoice) {
        if (sales == null) {
            return true;
        }
        boolean same = equal(sales.subtotal(), invoice.subtotal()) && equal(sales.totalDiscount(), invoice.totalDiscount())
                && equal(sales.totalTax(), invoice.totalTax()) && equal(sales.total(), invoice.total());
        if (!same) {
            LOG.warn("Totales distintos entre sales e invoicing para la venta {} (factura {}); la factura usa los de "
                            + "invoicing. sales: subtotal={}, descuento={}, impuestos={}, total={} | invoicing: "
                            + "subtotal={}, descuento={}, impuestos={}, total={}. Revisar las dos copias del cálculo "
                            + "(ADR 0012, D7).",
                    invoice.referenceCode().value(), invoice.id().value(), sales.subtotal(), sales.totalDiscount(),
                    sales.totalTax(), sales.total(), invoice.subtotal().amount(), invoice.totalDiscount().amount(),
                    invoice.totalTax().amount(), invoice.total().amount());
        }
        return same;
    }

    private static boolean equal(BigDecimal sales, Money invoicing) {
        return sales != null && sales.compareTo(invoicing.amount()) == 0;
    }
}
