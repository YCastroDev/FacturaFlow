package com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus;

import com.facturaflow.invoicing.domain.model.CustomerSnapshot;
import com.facturaflow.invoicing.domain.model.IdentificationDocument;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.domain.model.InvoiceLine;
import com.facturaflow.invoicing.domain.model.Municipality;
import com.facturaflow.invoicing.domain.model.PaymentTerms;
import com.facturaflow.invoicing.domain.model.PhoneNumber;
import com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusDtos.BillRequest;
import com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusDtos.Customer;
import com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusDtos.Item;
import com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusDtos.ItemTax;
import com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusDtos.PaymentDetail;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Traduce el agregado {@link Invoice} a la petición de {@code POST /v2/bills/validate}. Es la
 * frontera del Anti-Corruption Layer: el dominio no conoce ningún código de Factus.
 *
 * <ul>
 *   <li>{@code reference_code} es el {@code InvoiceId} (UUID): la idempotencia de Factus es ciega
 *       (spike 0001, sorpresa 1), así que se usa una clave que no se repite entre entornos ni tras
 *       resetear la base. El {@code referenceCode} de la venta es nuestra clave, no la de Factus.</li>
 *   <li>Precio unitario, cantidad y porcentajes se envían tal cual; Factus calcula los totales con el
 *       mismo redondeo que el dominio (spike 0001, sección 6).</li>
 *   <li>{@code payment_details.amount} es el total del dominio: Factus exige que coincida con el suyo.</li>
 * </ul>
 */
final class FactusBillTranslator {

    private final boolean sendEmail;
    private final Integer numberingRangeId;

    FactusBillTranslator(boolean sendEmail, Integer numberingRangeId) {
        this.sendEmail = sendEmail;
        this.numberingRangeId = numberingRangeId;
    }

    BillRequest toBill(Invoice invoice) {
        return new BillRequest(
                invoice.id().value().toString(),
                numberingRangeId,
                invoice.observation().orElse(null),
                sendEmail,
                customer(invoice.customer()),
                invoice.lines().stream().map(FactusBillTranslator::item).toList(),
                List.of(paymentDetail(invoice)));
    }

    private static Customer customer(CustomerSnapshot customer) {
        IdentificationDocument identification = customer.identification();
        return new Customer(
                FactusCodes.documentType(identification.type()),
                identification.number(),
                identification.checkDigit() == null ? null : identification.checkDigit().toString(),
                customer.companyName(),
                customer.names(),
                FactusCodes.legalOrganization(customer.personType()),
                FactusCodes.tribute(customer.fiscalProfile().taxScheme()),
                customer.fiscalProfile().responsibilities().stream().sorted()
                        .map(FactusCodes::responsibility).toList(),
                customer.optionalMunicipality().map(Municipality::code).orElse(null),
                customer.address(),
                customer.email(),
                customer.optionalPhone().map(PhoneNumber::value).orElse(null));
    }

    private static Item item(InvoiceLine line) {
        List<ItemTax> taxes = new ArrayList<>();
        if (line.excluded()) {
            taxes.add(new ItemTax(FactusCodes.IVA, BigDecimal.ZERO, true));
        }
        line.taxes().forEach(tax -> taxes.add(new ItemTax(FactusCodes.tax(tax.type()), tax.rate().percent(), null)));
        return new Item(
                line.code(),
                line.name(),
                line.quantity().value(),
                line.unitPrice().amount(),
                line.discountRate().percent(),
                FactusCodes.unitOfMeasure(line.unit()),
                FactusCodes.STANDARD_CODE_OWN,
                List.copyOf(taxes));
    }

    private static PaymentDetail paymentDetail(Invoice invoice) {
        String dueDate = switch (invoice.paymentTerms()) {
            case PaymentTerms.Cash _ -> null;
            case PaymentTerms.Credit(var date) -> date.toString();   // ISO yyyy-MM-dd, el formato que pide Factus
        };
        return new PaymentDetail(FactusCodes.paymentForm(invoice.paymentTerms()),
                FactusCodes.paymentMethod(invoice.paymentMethod()), invoice.total().amount(), dueDate);
    }
}
