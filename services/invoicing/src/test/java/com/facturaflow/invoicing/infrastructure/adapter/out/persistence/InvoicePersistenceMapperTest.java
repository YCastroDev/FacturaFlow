package com.facturaflow.invoicing.infrastructure.adapter.out.persistence;

import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.ISSUE_DATE;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.customer;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.electronicDocument;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.excludedLine;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

import com.facturaflow.invoicing.application.port.out.RequestHash;
import com.facturaflow.invoicing.domain.model.CustomerSnapshot;
import com.facturaflow.invoicing.domain.model.DocumentType;
import com.facturaflow.invoicing.domain.model.IdentificationDocument;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.domain.model.InvoiceId;
import com.facturaflow.invoicing.domain.model.LineTax;
import com.facturaflow.invoicing.domain.model.PaymentMethod;
import com.facturaflow.invoicing.domain.model.PaymentTerms;
import com.facturaflow.invoicing.domain.model.ReferenceCode;
import com.facturaflow.invoicing.domain.model.TaxType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests unitarios del mapeo a mano: sin Spring ni base de datos. */
class InvoicePersistenceMapperTest {

    private static final RequestHash HASH = new RequestHash("c".repeat(64));

    private final InvoicePersistenceMapper mapper = new InvoicePersistenceMapper();

    private static Invoice invoice(CustomerSnapshot customer, PaymentTerms terms, String observation) {
        return Invoice.create(InvoiceId.generate(), new ReferenceCode("SALE-0001"), customer, List.of(
                        line("2", "50000.00", "10", LineTax.of(TaxType.IVA, "19"), LineTax.of(TaxType.INC, "8")),
                        excludedLine("1", "20000.00"),
                        line("3", "1999.99", "5", "5")),
                PaymentMethod.CREDIT_CARD, terms, ISSUE_DATE, observation);
    }

    /** Simula lo que hace JPA al persistir: asignar la versión inicial. */
    private Invoice roundTrip(Invoice invoice) {
        InvoiceEntity entity = mapper.toNewEntity(invoice, HASH);
        entity.version = 0L;
        return mapper.toDomain(entity);
    }

    @Test
    @DisplayName("la huella de la petición viaja en la entidad y vuelve junto a la factura")
    void mapsRequestHash() {
        InvoiceEntity entity = mapper.toNewEntity(invoice(customer(), PaymentTerms.cash(), null), HASH);
        entity.version = 0L;

        assertThat(entity.requestHash).isEqualTo(HASH.value());
        assertThat(mapper.toStored(entity).requestHash()).isEqualTo(HASH);
    }

    @Test
    @DisplayName("una factura de contado con varios impuestos sobrevive la ida y vuelta campo por campo")
    void cashInvoiceRoundTrip() {
        Invoice original = invoice(customer(), PaymentTerms.cash(), "Entregar en portería");

        Invoice restored = roundTrip(original);

        assertThat(restored).usingRecursiveComparison().ignoringFields("version").isEqualTo(original);
        assertThat(restored.version()).contains(0L);
    }

    @Test
    @DisplayName("una factura a crédito guarda payment_type CREDIT y su fecha de vencimiento")
    void creditInvoiceMapsPaymentTypeAndDueDate() {
        Invoice original = invoice(customer(), PaymentTerms.credit(ISSUE_DATE.plusDays(30)), null);

        InvoiceEntity entity = mapper.toNewEntity(original, HASH);

        assertThat(entity.paymentType).isEqualTo("CREDIT");
        assertThat(entity.dueDate).isEqualTo(ISSUE_DATE.plusDays(30));
        entity.version = 0L;
        assertThat(mapper.toDomain(entity).paymentTerms()).isEqualTo(original.paymentTerms());
    }

    @Test
    @DisplayName("el orden de las líneas y de sus impuestos se conserva")
    void keepsOrderOfLinesAndTaxes() {
        InvoiceEntity entity = mapper.toNewEntity(invoice(customer(), PaymentTerms.cash(), null), HASH);

        assertThat(entity.lines).extracting(l -> l.code).containsExactly("SKU-1", "SKU-EXC", "SKU-1");
        assertThat(entity.lines.getFirst().taxes).extracting(t -> t.taxType).containsExactly("IVA", "INC");
        assertThat(entity.lines.get(1).ivaExcluded).isTrue();
    }

    @Test
    @DisplayName("una persona natural sin NIT se guarda sin dígito de verificación")
    void personWithoutCheckDigit() {
        CustomerSnapshot person = CustomerSnapshot.person(
                IdentificationDocument.of(DocumentType.CITIZENSHIP_CARD, "1020304050"), "Ana Pérez", null);
        Invoice original = invoice(person, PaymentTerms.cash(), null);

        InvoiceEntity entity = mapper.toNewEntity(original, HASH);

        assertThat(entity.customerCheckDigit).isNull();
        assertThat(entity.customerCompanyName).isNull();
        entity.version = 0L;
        assertThat(mapper.toDomain(entity).customer()).isEqualTo(person);
    }

    @Test
    @DisplayName("el documento electrónico y el total oficial de una factura emitida se mapean completos")
    void issuedInvoiceRoundTrip() {
        Invoice original = invoice(customer(), PaymentTerms.cash(), null);
        original.markAsIssued(electronicDocument("133084.97"));

        Invoice restored = roundTrip(original);

        assertThat(restored).usingRecursiveComparison().ignoringFields("version").isEqualTo(original);
    }

    @Test
    @DisplayName("el motivo de rechazo se mapea y no deja rastro de documento electrónico")
    void rejectedInvoiceRoundTrip() {
        Invoice original = invoice(customer(), PaymentTerms.cash(), null);
        original.markAsRejected("Regla FAD06");

        InvoiceEntity entity = mapper.toNewEntity(original, HASH);

        assertThat(entity.status).isEqualTo("REJECTED");
        assertThat(entity.edocNumber).isNull();
        entity.version = 0L;
        assertThat(mapper.toDomain(entity).rejectionReason()).hasValue("Regla FAD06");
    }

    @Test
    @DisplayName("solo el estado, el documento y el motivo se copian al actualizar una entidad existente")
    void applyMutableStateOnlyTouchesMutableColumns() {
        Invoice original = invoice(customer(), PaymentTerms.cash(), null);
        InvoiceEntity entity = mapper.toNewEntity(original, HASH);
        var linesBefore = entity.lines;
        original.markAsIssued(electronicDocument("133084.97"));

        mapper.applyMutableState(original, entity);

        assertThat(entity.status).isEqualTo("ISSUED");
        assertThat(entity.edocCufe).isEqualTo(original.electronicDocument().orElseThrow().cufe());
        assertThat(entity.lines).isSameAs(linesBefore);
    }
}
