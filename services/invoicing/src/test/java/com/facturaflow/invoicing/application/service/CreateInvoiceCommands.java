package com.facturaflow.invoicing.application.service;

import com.facturaflow.invoicing.application.port.in.CreateInvoiceCommand;
import com.facturaflow.invoicing.domain.model.DocumentType;
import com.facturaflow.invoicing.domain.model.FiscalResponsibility;
import com.facturaflow.invoicing.domain.model.PaymentMethod;
import com.facturaflow.invoicing.domain.model.PersonType;
import com.facturaflow.invoicing.domain.model.TaxScheme;
import com.facturaflow.invoicing.domain.model.TaxType;
import com.facturaflow.invoicing.domain.model.UnitOfMeasure;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Comandos válidos por defecto para los tests de casos de uso. */
final class CreateInvoiceCommands {

    static final String REFERENCE_CODE = "SALE-0001";

    private CreateInvoiceCommands() {
    }

    static CreateInvoiceCommand.Customer customer() {
        return new CreateInvoiceCommand.Customer(DocumentType.NIT, "890903938", 8, PersonType.LEGAL_ENTITY,
                "Cliente Ejemplo S.A.S.", null, TaxScheme.IVA, List.of(FiscalResponsibility.LARGE_TAXPAYER),
                "facturacion@cliente.example", "11001", "Calle 10 # 20-30", "6015551234");
    }

    static CreateInvoiceCommand.Line line(String quantity, String unitPrice) {
        return new CreateInvoiceCommand.Line("SKU-1", "Producto", new BigDecimal(quantity), new BigDecimal(unitPrice),
                null, UnitOfMeasure.UNIT, false, List.of(new CreateInvoiceCommand.Tax(TaxType.IVA, new BigDecimal("19"))));
    }

    static CreateInvoiceCommand command() {
        return command(REFERENCE_CODE, "Entregar en portería");
    }

    static CreateInvoiceCommand command(String referenceCode, String observation) {
        return new CreateInvoiceCommand(referenceCode, customer(), List.of(line("2", "50000.00")),
                PaymentMethod.CASH, new CreateInvoiceCommand.CashTerms(), observation);
    }

    static CreateInvoiceCommand creditCommand(LocalDate dueDate) {
        return new CreateInvoiceCommand(REFERENCE_CODE, customer(), List.of(line("2", "50000.00")),
                PaymentMethod.BANK_TRANSFER, new CreateInvoiceCommand.CreditTerms(dueDate), null);
    }
}
