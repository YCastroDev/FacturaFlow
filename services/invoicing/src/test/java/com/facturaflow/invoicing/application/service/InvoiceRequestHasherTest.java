package com.facturaflow.invoicing.application.service;

import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.ISSUE_DATE;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.customerWithLocation;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.excludedLine;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

import com.facturaflow.invoicing.application.port.in.CreateInvoiceCommand;
import com.facturaflow.invoicing.domain.model.CustomerSnapshot;
import com.facturaflow.invoicing.domain.model.DiscountRate;
import com.facturaflow.invoicing.domain.model.DocumentType;
import com.facturaflow.invoicing.domain.model.FiscalProfile;
import com.facturaflow.invoicing.domain.model.FiscalResponsibility;
import com.facturaflow.invoicing.domain.model.IdentificationDocument;
import com.facturaflow.invoicing.domain.model.InvoiceLine;
import com.facturaflow.invoicing.domain.model.LineTax;
import com.facturaflow.invoicing.domain.model.Money;
import com.facturaflow.invoicing.domain.model.Municipality;
import com.facturaflow.invoicing.domain.model.PaymentMethod;
import com.facturaflow.invoicing.domain.model.PaymentTerms;
import com.facturaflow.invoicing.domain.model.PersonType;
import com.facturaflow.invoicing.domain.model.PhoneNumber;
import com.facturaflow.invoicing.domain.model.Quantity;
import com.facturaflow.invoicing.domain.model.TaxScheme;
import com.facturaflow.invoicing.domain.model.TaxType;
import com.facturaflow.invoicing.domain.model.UnitOfMeasure;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class InvoiceRequestHasherTest {

    private static final InvoiceLine LINE_A = line("2", "50000.00", "10", LineTax.of(TaxType.IVA, "19"),
            LineTax.of(TaxType.INC, "8"));
    private static final InvoiceLine LINE_B = excludedLine("1", "20000.00");

    private static final CustomerSnapshot CUSTOMER = customerWithLocation();

    private static InvoiceContent base() {
        return new InvoiceContent(CUSTOMER, List.of(LINE_A, LINE_B), PaymentMethod.CASH, PaymentTerms.cash(),
                "Entregar en portería");
    }

    /** Cliente con todos sus componentes explícitos, para variar uno a la vez. */
    private static CustomerSnapshot customerWith(IdentificationDocument identification, PersonType personType,
                                                 String companyName, String names, FiscalProfile profile,
                                                 String email, Municipality municipality, String address,
                                                 PhoneNumber phone) {
        return new CustomerSnapshot(identification, personType, companyName, names, profile, email, municipality,
                address, phone);
    }

    private static InvoiceContent withCustomer(CustomerSnapshot customer) {
        InvoiceContent base = base();
        return new InvoiceContent(customer, base.lines(), base.paymentMethod(), base.paymentTerms(),
                base.observation());
    }

    private static InvoiceContent withFirstLine(InvoiceLine line) {
        InvoiceContent base = base();
        return new InvoiceContent(base.customer(), List.of(line, LINE_B), base.paymentMethod(),
                base.paymentTerms(), base.observation());
    }

    private static InvoiceLine lineA(String code, String name, String quantity, String price, String discount,
                                     UnitOfMeasure unit, List<LineTax> taxes) {
        return new InvoiceLine(code, name, Quantity.of(quantity), Money.of(price), DiscountRate.of(discount), taxes,
                unit, false);
    }

    @Test
    @DisplayName("la huella son 64 caracteres hexadecimales y es estable para el mismo contenido")
    void hashIsStable() {
        assertThat(InvoiceRequestHasher.hash(base()).value()).matches("[0-9a-f]{64}");
        assertThat(InvoiceRequestHasher.hash(base())).isEqualTo(InvoiceRequestHasher.hash(base()));
    }

    @Test
    @DisplayName("el texto canónico declara su versión de formato")
    void canonicalFormIsVersioned() {
        assertThat(InvoiceRequestHasher.canonicalForm(base())).startsWith("18:invoice-request-v2;");
    }

    @Test
    @DisplayName("el orden de los impuestos de una línea no cambia la huella")
    void taxOrderDoesNotMatter() {
        InvoiceLine reordered = new InvoiceLine(LINE_A.code(), LINE_A.name(), LINE_A.quantity(), LINE_A.unitPrice(),
                LINE_A.discountRate(), List.of(LineTax.of(TaxType.INC, "8"), LineTax.of(TaxType.IVA, "19")),
                LINE_A.unit(), false);

        assertThat(InvoiceRequestHasher.hash(withFirstLine(reordered))).isEqualTo(InvoiceRequestHasher.hash(base()));
    }

    @Test
    @DisplayName("peticiones que solo difieren en formato (decimales, espacios, vacíos, orden o repetición de responsabilidades, separadores del teléfono) tienen la misma huella")
    void equivalentRequestsShareHash() {
        CreateInvoiceCommand plain = new CreateInvoiceCommand("SALE-1",
                new CreateInvoiceCommand.Customer(DocumentType.NIT, "890903938", 8, PersonType.LEGAL_ENTITY,
                        "Cliente S.A.S.", null, TaxScheme.IVA,
                        List.of(FiscalResponsibility.LARGE_TAXPAYER, FiscalResponsibility.SELF_WITHHOLDER), null,
                        "11001", "Calle 1", "6015551234"),
                List.of(new CreateInvoiceCommand.Line("SKU-1", "Producto", new BigDecimal("2"), new BigDecimal("1000"),
                        null, UnitOfMeasure.UNIT, false,
                        List.of(new CreateInvoiceCommand.Tax(TaxType.IVA, new BigDecimal("19"))))),
                PaymentMethod.CASH, new CreateInvoiceCommand.CashTerms(), null);
        CreateInvoiceCommand noisy = new CreateInvoiceCommand("SALE-1",
                new CreateInvoiceCommand.Customer(DocumentType.NIT, " 890903938 ", 8, PersonType.LEGAL_ENTITY,
                        "  Cliente S.A.S. ", "  ", TaxScheme.IVA,
                        List.of(FiscalResponsibility.SELF_WITHHOLDER, FiscalResponsibility.LARGE_TAXPAYER,
                                FiscalResponsibility.SELF_WITHHOLDER), "", " 11001 ", " Calle 1 ", "(601) 555-1234"),
                List.of(new CreateInvoiceCommand.Line(" SKU-1 ", "Producto  ", new BigDecimal("2.00"),
                        new BigDecimal("1000.0"), new BigDecimal("0.00"), UnitOfMeasure.UNIT, false,
                        List.of(new CreateInvoiceCommand.Tax(TaxType.IVA, new BigDecimal("19.00"))))),
                PaymentMethod.CASH, new CreateInvoiceCommand.CashTerms(), "   ");

        assertThat(InvoiceRequestHasher.hash(InvoiceContentMapper.toContent(noisy)))
                .isEqualTo(InvoiceRequestHasher.hash(InvoiceContentMapper.toContent(plain)));
    }

    static Stream<Arguments> differentContents() {
        CustomerSnapshot c = CUSTOMER;
        IdentificationDocument nit = c.identification();
        return Stream.of(
                Arguments.of("tipo de documento", withCustomer(customerWith(
                        IdentificationDocument.of(DocumentType.CITIZENSHIP_CARD, nit.number()), c.personType(),
                        c.companyName(), null, c.fiscalProfile(), c.email(), c.municipality(), c.address(), c.phone()))),
                Arguments.of("número de documento", withCustomer(customerWith(IdentificationDocument.nit("900373115", 3),
                        c.personType(), c.companyName(), null, c.fiscalProfile(), c.email(), c.municipality(),
                        c.address(), c.phone()))),
                Arguments.of("tipo de persona", withCustomer(customerWith(nit, PersonType.NATURAL_PERSON, null,
                        c.companyName(), c.fiscalProfile(), c.email(), c.municipality(), c.address(), c.phone()))),
                Arguments.of("razón social", withCustomer(customerWith(nit, c.personType(), "Otra S.A.S.", null,
                        c.fiscalProfile(), c.email(), c.municipality(), c.address(), c.phone()))),
                Arguments.of("responsabilidad tributaria", withCustomer(customerWith(nit, c.personType(),
                        c.companyName(), null, new FiscalProfile(TaxScheme.NOT_APPLICABLE,
                                c.fiscalProfile().responsibilities()), c.email(), c.municipality(), c.address(),
                        c.phone()))),
                Arguments.of("una responsabilidad fiscal menos", withCustomer(customerWith(nit, c.personType(),
                        c.companyName(), null, FiscalProfile.of(TaxScheme.IVA, FiscalResponsibility.LARGE_TAXPAYER),
                        c.email(), c.municipality(), c.address(), c.phone()))),
                Arguments.of("email", withCustomer(customerWith(nit, c.personType(), c.companyName(), null,
                        c.fiscalProfile(), "otro@cliente.example", c.municipality(), c.address(), c.phone()))),
                Arguments.of("email en mayúsculas", withCustomer(customerWith(nit, c.personType(), c.companyName(),
                        null, c.fiscalProfile(), c.email().toUpperCase(), c.municipality(), c.address(), c.phone()))),
                Arguments.of("sin email", withCustomer(customerWith(nit, c.personType(), c.companyName(), null,
                        c.fiscalProfile(), null, c.municipality(), c.address(), c.phone()))),
                Arguments.of("municipio", withCustomer(customerWith(nit, c.personType(), c.companyName(), null,
                        c.fiscalProfile(), c.email(), Municipality.of("05001"), c.address(), c.phone()))),
                Arguments.of("sin municipio", withCustomer(customerWith(nit, c.personType(), c.companyName(), null,
                        c.fiscalProfile(), c.email(), null, c.address(), c.phone()))),
                Arguments.of("dirección", withCustomer(customerWith(nit, c.personType(), c.companyName(), null,
                        c.fiscalProfile(), c.email(), c.municipality(), "Carrera 7 # 1-1", c.phone()))),
                Arguments.of("sin dirección", withCustomer(customerWith(nit, c.personType(), c.companyName(), null,
                        c.fiscalProfile(), c.email(), c.municipality(), null, c.phone()))),
                Arguments.of("teléfono", withCustomer(customerWith(nit, c.personType(), c.companyName(), null,
                        c.fiscalProfile(), c.email(), c.municipality(), c.address(), PhoneNumber.of("3001234567")))),
                Arguments.of("sin teléfono", withCustomer(customerWith(nit, c.personType(), c.companyName(), null,
                        c.fiscalProfile(), c.email(), c.municipality(), c.address(), null))),
                Arguments.of("medio de pago", new InvoiceContent(CUSTOMER, base().lines(),
                        PaymentMethod.DEBIT_CARD, PaymentTerms.cash(), base().observation())),
                Arguments.of("forma de pago", new InvoiceContent(CUSTOMER, base().lines(), PaymentMethod.CASH,
                        PaymentTerms.credit(ISSUE_DATE.plusDays(30)), base().observation())),
                Arguments.of("observación", new InvoiceContent(CUSTOMER, base().lines(), PaymentMethod.CASH,
                        PaymentTerms.cash(), "Otra observación")),
                Arguments.of("sin observación", new InvoiceContent(CUSTOMER, base().lines(), PaymentMethod.CASH,
                        PaymentTerms.cash(), null)),
                Arguments.of("código de línea", withFirstLine(lineA("SKU-2", "Producto", "2", "50000.00", "10",
                        UnitOfMeasure.UNIT, LINE_A.taxes()))),
                Arguments.of("nombre de línea", withFirstLine(lineA("SKU-1", "Otro", "2", "50000.00", "10",
                        UnitOfMeasure.UNIT, LINE_A.taxes()))),
                Arguments.of("cantidad", withFirstLine(lineA("SKU-1", "Producto", "3", "50000.00", "10",
                        UnitOfMeasure.UNIT, LINE_A.taxes()))),
                Arguments.of("precio", withFirstLine(lineA("SKU-1", "Producto", "2", "50000.01", "10",
                        UnitOfMeasure.UNIT, LINE_A.taxes()))),
                Arguments.of("descuento", withFirstLine(lineA("SKU-1", "Producto", "2", "50000.00", "11",
                        UnitOfMeasure.UNIT, LINE_A.taxes()))),
                Arguments.of("unidad", withFirstLine(lineA("SKU-1", "Producto", "2", "50000.00", "10",
                        UnitOfMeasure.KILOGRAM, LINE_A.taxes()))),
                Arguments.of("tarifa de impuesto", withFirstLine(lineA("SKU-1", "Producto", "2", "50000.00", "10",
                        UnitOfMeasure.UNIT, List.of(LineTax.of(TaxType.IVA, "5"), LineTax.of(TaxType.INC, "8"))))),
                Arguments.of("un impuesto menos", withFirstLine(lineA("SKU-1", "Producto", "2", "50000.00", "10",
                        UnitOfMeasure.UNIT, List.of(LineTax.of(TaxType.IVA, "19"))))),
                Arguments.of("excluida de IVA", new InvoiceContent(CUSTOMER, List.of(LINE_A,
                        new InvoiceLine(LINE_B.code(), LINE_B.name(), LINE_B.quantity(), LINE_B.unitPrice(),
                                LINE_B.discountRate(), List.of(), LINE_B.unit(), false)),
                        PaymentMethod.CASH, PaymentTerms.cash(), base().observation())),
                Arguments.of("orden de las líneas", new InvoiceContent(CUSTOMER, List.of(LINE_B, LINE_A),
                        PaymentMethod.CASH, PaymentTerms.cash(), base().observation())),
                Arguments.of("una línea más", new InvoiceContent(CUSTOMER, List.of(LINE_A, LINE_B, LINE_B),
                        PaymentMethod.CASH, PaymentTerms.cash(), base().observation())));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("differentContents")
    @DisplayName("cambiar cualquier dato de la factura cambia la huella")
    void anyChangeChangesHash(String change, InvoiceContent changed) {
        assertThat(InvoiceRequestHasher.hash(changed)).isNotEqualTo(InvoiceRequestHasher.hash(base()));
    }

    @Test
    @DisplayName("un texto con separadores no se confunde con dos campos (codificación con longitud)")
    void lengthPrefixPreventsAmbiguity() {
        CustomerSnapshot c = CUSTOMER;
        CustomerSnapshot oneField = customerWith(c.identification(), c.personType(), c.companyName(), null,
                c.fiscalProfile(), c.email(), c.municipality(), "Calle 1;10:3001234567", null);
        CustomerSnapshot twoFields = customerWith(c.identification(), c.personType(), c.companyName(), null,
                c.fiscalProfile(), c.email(), c.municipality(), "Calle 1", PhoneNumber.of("3001234567"));

        assertThat(InvoiceRequestHasher.hash(withCustomer(oneField)))
                .isNotEqualTo(InvoiceRequestHasher.hash(withCustomer(twoFields)));
    }
}
