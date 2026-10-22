package com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus;

import static com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusTestData.cashInvoice;
import static com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusTestData.creditInvoice;
import static com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusTestData.fixture;
import static org.assertj.core.api.Assertions.assertThat;

import com.facturaflow.invoicing.domain.model.CustomerSnapshot;
import com.facturaflow.invoicing.domain.model.DiscountRate;
import com.facturaflow.invoicing.domain.model.FiscalProfile;
import com.facturaflow.invoicing.domain.model.FiscalResponsibility;
import com.facturaflow.invoicing.domain.model.IdentificationDocument;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.domain.model.InvoiceId;
import com.facturaflow.invoicing.domain.model.InvoiceLine;
import com.facturaflow.invoicing.domain.model.LineTax;
import com.facturaflow.invoicing.domain.model.Money;
import com.facturaflow.invoicing.domain.model.PaymentMethod;
import com.facturaflow.invoicing.domain.model.PaymentTerms;
import com.facturaflow.invoicing.domain.model.Quantity;
import com.facturaflow.invoicing.domain.model.ReferenceCode;
import com.facturaflow.invoicing.domain.model.TaxScheme;
import com.facturaflow.invoicing.domain.model.TaxType;
import com.facturaflow.invoicing.domain.model.UnitOfMeasure;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import tools.jackson.databind.JsonNode;

class FactusBillTranslatorTest {

    private static final UUID ID = UUID.fromString("7f1c2a4e-0000-4000-8000-0000000000aa");

    private final FactusBillTranslator translator = new FactusBillTranslator(false, null);

    private static String json(Object request) {
        return FactusJson.MAPPER.writeValueAsString(request);
    }

    @Test
    @DisplayName("la factura de contado se traduce exactamente a la petición que Factus aceptó en el spike")
    void cashInvoiceMatchesAcceptedPayload() throws Exception {
        String actual = json(translator.toBill(cashInvoice(ID)));

        JSONAssert.assertEquals(fixture("request-cash.json", ID), actual, JSONCompareMode.STRICT);
    }

    @Test
    @DisplayName("la factura a crédito con IVA + INC y una línea excluida se traduce a la petición aceptada")
    void creditInvoiceMatchesAcceptedPayload() throws Exception {
        String actual = json(translator.toBill(creditInvoice(ID)));

        JSONAssert.assertEquals(fixture("request-credit.json", ID), actual, JSONCompareMode.STRICT);
    }

    @Test
    @DisplayName("el reference_code es el id de la factura (UUID de 36 caracteres), no el código de la venta")
    void referenceCodeIsTheInvoiceId() {
        JsonNode bill = FactusJson.MAPPER.valueToTree(translator.toBill(cashInvoice(ID)));

        assertThat(bill.get("reference_code").asString()).isEqualTo(ID.toString()).hasSize(36);
    }

    @Test
    @DisplayName("send_email y numbering_range_id salen de la configuración")
    void configurationGoesIntoTheRequest() {
        JsonNode bill = FactusJson.MAPPER.valueToTree(new FactusBillTranslator(true, 389).toBill(cashInvoice(ID)));

        assertThat(bill.get("send_email").asBoolean()).isTrue();
        assertThat(bill.get("numbering_range_id").asInt()).isEqualTo(389);
    }

    @Test
    @DisplayName("los datos opcionales ausentes no se envían, y las responsabilidades van en un orden estable")
    void omitsMissingOptionalData() {
        CustomerSnapshot customer = CustomerSnapshot.company(IdentificationDocument.nit("890903938", 8),
                "Cliente Ejemplo S.A.S.", FiscalProfile.of(TaxScheme.IVA, FiscalResponsibility.SELF_WITHHOLDER,
                        FiscalResponsibility.LARGE_TAXPAYER), null);
        Invoice invoice = Invoice.create(new InvoiceId(ID), new ReferenceCode("VENTA-1"), customer,
                List.of(new InvoiceLine("SKU-1", "Producto", Quantity.of("1"), Money.of("1000"), DiscountRate.NONE,
                        List.of(), UnitOfMeasure.KILOGRAM, false)),
                PaymentMethod.CREDIT_CARD, PaymentTerms.cash(), FactusTestData.ISSUE_DATE, null);

        JsonNode bill = FactusJson.MAPPER.valueToTree(translator.toBill(invoice));

        assertThat(bill.has("observation")).isFalse();
        assertThat(bill.has("numbering_range_id")).isFalse();
        JsonNode sent = bill.get("customer");
        assertThat(sent.propertyNames()).doesNotContain("names", "email", "municipality_code", "address", "phone");
        assertThat(sent.get("tribute_code").asString()).isEqualTo("01");
        assertThat(sent.get("responsibilities").toString()).isEqualTo("[\"O-13\",\"O-15\"]");
        assertThat(bill.get("items").get(0).get("unit_measure_code").asString()).isEqualTo("KGM");
        assertThat(bill.get("items").get(0).get("taxes").isEmpty()).isTrue();
        assertThat(bill.get("payment_details").get(0).has("due_date")).isFalse();
    }

    @Test
    @DisplayName("una línea excluida de IVA con INC envía la marca de excluida y además el INC")
    void excludedLineKeepsOtherTaxes() {
        Invoice invoice = Invoice.create(new InvoiceId(ID), new ReferenceCode("VENTA-1"),
                cashInvoice(ID).customer(),
                List.of(new InvoiceLine("SKU-1", "Producto", Quantity.of("1"), Money.of("1000"), DiscountRate.NONE,
                        List.of(LineTax.of(TaxType.INC, "8")), UnitOfMeasure.UNIT, true)),
                PaymentMethod.CASH, PaymentTerms.cash(), FactusTestData.ISSUE_DATE, null);

        JsonNode taxes = FactusJson.MAPPER.valueToTree(translator.toBill(invoice)).get("items").get(0).get("taxes");

        assertThat(taxes.toString()).isEqualTo(
                "[{\"code\":\"01\",\"rate\":0,\"is_excluded\":true},{\"code\":\"04\",\"rate\":8.00}]");
    }
}
