package com.facturaflow.sales.application.port.in;

import com.facturaflow.sales.domain.model.DocumentType;
import com.facturaflow.sales.domain.model.PaymentMethod;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Datos para crear una venta, con tipos simples y enums del dominio. Solo lo que decide el vendedor:
 * precios, datos del producto y del cliente salen SIEMPRE de catalog (ADR 0012). La fecha de la venta la
 * pone el caso de uso con su reloj.
 *
 * <p>{@code soldByUserId} y {@code soldByUsername} identifican a quien hace la venta (ADR 0018, decisión 13). No
 * vienen del cuerpo de la petición: el adaptador REST los toma del token ({@code sub} y {@code preferred_username}),
 * así el caso de uso no sabe de Spring Security. No entran en la huella de idempotencia.
 */
public record CreateSaleCommand(String referenceCode, DocumentType customerDocumentType,
                                String customerDocumentNumber, List<Line> lines, PaymentMethod paymentMethod,
                                Terms paymentTerms, String observation, String soldByUserId,
                                String soldByUsername) {

    /** @param discountRate porcentaje; {@code null} equivale a sin descuento */
    public record Line(String productCode, BigDecimal quantity, BigDecimal discountRate) {
    }

    /** Forma de pago: de contado o a crédito con fecha de vencimiento. */
    public sealed interface Terms {
    }

    public record CashTerms() implements Terms {
    }

    public record CreditTerms(LocalDate dueDate) implements Terms {
    }
}
