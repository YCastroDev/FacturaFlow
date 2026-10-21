package com.facturaflow.invoicing.application.port.in;

import com.facturaflow.invoicing.domain.model.DocumentType;
import com.facturaflow.invoicing.domain.model.PaymentMethod;
import com.facturaflow.invoicing.domain.model.TaxType;
import com.facturaflow.invoicing.domain.model.UnitOfMeasure;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Datos para crear una factura, con tipos simples y enums del dominio. No se valida aquí:
 * las reglas de negocio las aplica el dominio dentro del caso de uso. La fecha de emisión
 * no viaja en el comando: la pone el caso de uso con su reloj.
 *
 * @param observation opcional; puede activar simulaciones del proveedor falso (ADR 0006)
 */
public record CreateInvoiceCommand(String referenceCode, Customer customer, List<Line> lines,
                                   PaymentMethod paymentMethod, Terms paymentTerms, String observation) {

    /** @param checkDigit solo para NIT; {@code null} en los demás tipos */
    public record Customer(DocumentType documentType, String documentNumber, Integer checkDigit,
                           String companyName, String names, String email) {
    }

    /** @param discountRate porcentaje; {@code null} equivale a sin descuento */
    public record Line(String code, String name, BigDecimal quantity, BigDecimal unitPrice, BigDecimal discountRate,
                       UnitOfMeasure unit, boolean ivaExcluded, List<Tax> taxes) {
    }

    public record Tax(TaxType type, BigDecimal rate) {
    }

    /** Forma de pago: de contado o a crédito con fecha de vencimiento. */
    public sealed interface Terms {
    }

    public record CashTerms() implements Terms {
    }

    public record CreditTerms(LocalDate dueDate) implements Terms {
    }
}
