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
 */
public record CreateSaleCommand(String referenceCode, DocumentType customerDocumentType,
                                String customerDocumentNumber, List<Line> lines, PaymentMethod paymentMethod,
                                Terms paymentTerms, String observation) {

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
