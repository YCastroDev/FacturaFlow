package com.facturaflow.sales.application.service;

import com.facturaflow.sales.application.port.in.CreateSaleCommand;
import com.facturaflow.sales.domain.exception.InvalidSaleException;
import com.facturaflow.sales.domain.exception.InvalidSaleLineException;
import com.facturaflow.sales.domain.model.CustomerDocumentRef;
import com.facturaflow.sales.domain.model.DiscountRate;
import com.facturaflow.sales.domain.model.PaymentMethod;
import com.facturaflow.sales.domain.model.PaymentTerms;
import com.facturaflow.sales.domain.model.Quantity;
import com.facturaflow.sales.domain.model.ReferenceCode;
import com.facturaflow.sales.domain.model.Sale;
import com.facturaflow.sales.domain.model.SaleLine;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * La petición de venta ya validada y normalizada con los value objects del dominio, ANTES de consultar a
 * catalog: un dato mal formado responde 422 sin gastar llamadas. De aquí sale la huella (idempotencia).
 */
record SaleRequest(ReferenceCode referenceCode, CustomerDocumentRef customer, List<Line> lines,
                   PaymentMethod paymentMethod, PaymentTerms paymentTerms, String observation) {

    /** Lo que el vendedor pide de un producto: código (como lo escribió), cantidad y descuento. */
    record Line(String productCode, Quantity quantity, DiscountRate discountRate) {

        /** Clave para cruzar con catalog: los códigos no distinguen mayúsculas. */
        String key() {
            return productCode.toLowerCase(Locale.ROOT);
        }
    }

    static SaleRequest from(CreateSaleCommand command) {
        ReferenceCode referenceCode = new ReferenceCode(command.referenceCode());
        CustomerDocumentRef customer = new CustomerDocumentRef(command.customerDocumentType(),
                command.customerDocumentNumber());
        List<CreateSaleCommand.Line> lines = command.lines();
        if (lines == null || lines.isEmpty() || lines.size() > Sale.MAX_LINES) {
            throw InvalidSaleException.lineCount(lines == null ? 0 : lines.size(), Sale.MAX_LINES);
        }
        if (lines.stream().anyMatch(Objects::isNull)) {
            throw InvalidSaleException.missing("líneas no nulas");
        }
        List<Line> normalized = lines.stream().map(SaleRequest::line).toList();
        if (command.paymentMethod() == null) {
            throw InvalidSaleException.missing("un medio de pago");
        }
        PaymentTerms terms = switch (command.paymentTerms()) {
            case null -> throw InvalidSaleException.missing("una forma de pago");
            case CreateSaleCommand.CashTerms ignored -> PaymentTerms.cash();
            case CreateSaleCommand.CreditTerms credit -> PaymentTerms.credit(credit.dueDate());
        };
        return new SaleRequest(referenceCode, customer, normalized, command.paymentMethod(), terms,
                Sale.normalizeObservation(command.observation()));
    }

    private static Line line(CreateSaleCommand.Line line) {
        String code = line.productCode() == null ? null : line.productCode().strip();
        if (code == null || code.isEmpty()) {
            throw InvalidSaleLineException.missing("el código del producto");
        }
        if (code.length() > SaleLine.MAX_CODE_LENGTH) {
            throw InvalidSaleLineException.tooLong("código del producto", SaleLine.MAX_CODE_LENGTH);
        }
        DiscountRate discount = line.discountRate() == null ? DiscountRate.NONE : new DiscountRate(line.discountRate());
        return new Line(code, new Quantity(line.quantity()), discount);
    }
}
