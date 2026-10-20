package com.facturaflow.invoicing.infrastructure.adapter.out.persistence;

import com.facturaflow.invoicing.domain.model.CustomerSnapshot;
import com.facturaflow.invoicing.domain.model.DiscountRate;
import com.facturaflow.invoicing.domain.model.DocumentType;
import com.facturaflow.invoicing.domain.model.ElectronicDocument;
import com.facturaflow.invoicing.domain.model.IdentificationDocument;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.domain.model.InvoiceId;
import com.facturaflow.invoicing.domain.model.InvoiceLine;
import com.facturaflow.invoicing.domain.model.InvoiceStatus;
import com.facturaflow.invoicing.domain.model.LineTax;
import com.facturaflow.invoicing.domain.model.Money;
import com.facturaflow.invoicing.domain.model.PaymentMethod;
import com.facturaflow.invoicing.domain.model.PaymentTerms;
import com.facturaflow.invoicing.domain.model.Quantity;
import com.facturaflow.invoicing.domain.model.ReferenceCode;
import com.facturaflow.invoicing.domain.model.TaxRate;
import com.facturaflow.invoicing.domain.model.TaxType;
import com.facturaflow.invoicing.domain.model.UnitOfMeasure;
import java.net.URI;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Traducción a mano entre el agregado {@link Invoice} y las entidades JPA. Es el único
 * lugar que conoce ambos modelos; los enums viajan por su nombre.
 */
@Component
class InvoicePersistenceMapper {

    private static final String CASH = "CASH";
    private static final String CREDIT = "CREDIT";

    /** Entidad para una factura que aún no existe en la base (versión vacía: JPA la inicializa). */
    InvoiceEntity toNewEntity(Invoice invoice) {
        InvoiceEntity entity = new InvoiceEntity();
        entity.id = invoice.id().value();
        entity.referenceCode = invoice.referenceCode().value();
        entity.issueDate = invoice.issueDate();
        entity.paymentMethod = invoice.paymentMethod().name();
        switch (invoice.paymentTerms()) {
            case PaymentTerms.Cash _ -> entity.paymentType = CASH;
            case PaymentTerms.Credit(var dueDate) -> {
                entity.paymentType = CREDIT;
                entity.dueDate = dueDate;
            }
        }
        entity.observation = invoice.observation().orElse(null);

        CustomerSnapshot customer = invoice.customer();
        IdentificationDocument identification = customer.identification();
        entity.customerDocumentType = identification.type().name();
        entity.customerDocumentNumber = identification.number();
        entity.customerCheckDigit = identification.checkDigit() == null ? null : identification.checkDigit().shortValue();
        entity.customerCompanyName = customer.companyName();
        entity.customerNames = customer.names();
        entity.customerEmail = customer.email();

        for (InvoiceLine line : invoice.lines()) {
            entity.lines.add(toLineEntity(line));
        }
        applyMutableState(invoice, entity);
        return entity;
    }

    /**
     * Copia lo único que puede cambiar después de crear una factura: estado, documento
     * electrónico y motivo de rechazo. Las líneas son inmutables (ADR 0005).
     */
    void applyMutableState(Invoice invoice, InvoiceEntity entity) {
        entity.status = invoice.status().name();
        ElectronicDocument document = invoice.electronicDocument().orElse(null);
        entity.edocNumber = document == null ? null : document.number();
        entity.edocCufe = document == null ? null : document.cufe();
        entity.edocPdfUrl = document == null ? null : document.pdfUrl().toString();
        entity.edocValidatedAt = document == null ? null : document.validatedAt();
        entity.edocOfficialTotal = document == null ? null : document.officialTotal().amount();
        entity.rejectionReason = invoice.rejectionReason().orElse(null);
    }

    /** Reconstruye el agregado; {@link Invoice#reconstitute} rechaza cualquier estado incoherente. */
    Invoice toDomain(InvoiceEntity entity) {
        IdentificationDocument identification = new IdentificationDocument(
                DocumentType.valueOf(entity.customerDocumentType), entity.customerDocumentNumber,
                entity.customerCheckDigit == null ? null : entity.customerCheckDigit.intValue());
        CustomerSnapshot customer = new CustomerSnapshot(identification, entity.customerCompanyName,
                entity.customerNames, entity.customerEmail);
        PaymentTerms paymentTerms = switch (entity.paymentType) {
            case CASH -> PaymentTerms.cash();
            case CREDIT -> PaymentTerms.credit(entity.dueDate);
            default -> throw new IllegalStateException("payment_type desconocido: " + entity.paymentType);
        };
        ElectronicDocument document = entity.edocNumber == null ? null : new ElectronicDocument(
                entity.edocNumber, entity.edocCufe, URI.create(entity.edocPdfUrl), entity.edocValidatedAt,
                Money.of(entity.edocOfficialTotal));

        return Invoice.reconstitute(
                new InvoiceId(entity.id),
                new ReferenceCode(entity.referenceCode),
                customer,
                entity.lines.stream().map(this::toDomainLine).toList(),
                PaymentMethod.valueOf(entity.paymentMethod),
                paymentTerms,
                entity.issueDate,
                entity.observation,
                InvoiceStatus.valueOf(entity.status),
                document,
                entity.rejectionReason,
                entity.version);
    }

    private InvoiceLineEntity toLineEntity(InvoiceLine line) {
        InvoiceLineEntity entity = new InvoiceLineEntity();
        entity.id = UUID.randomUUID();
        entity.code = line.code();
        entity.name = line.name();
        entity.quantity = line.quantity().value();
        entity.unitPrice = line.unitPrice().amount();
        entity.discountRate = line.discountRate().percent();
        entity.unitOfMeasure = line.unit().name();
        entity.ivaExcluded = line.excluded();
        for (LineTax tax : line.taxes()) {
            entity.taxes.add(new LineTaxEmbeddable(tax.type().name(), tax.rate().percent()));
        }
        return entity;
    }

    private InvoiceLine toDomainLine(InvoiceLineEntity entity) {
        return new InvoiceLine(
                entity.code,
                entity.name,
                new Quantity(entity.quantity),
                Money.of(entity.unitPrice),
                new DiscountRate(entity.discountRate),
                entity.taxes.stream()
                        .map(tax -> new LineTax(TaxType.valueOf(tax.taxType), new TaxRate(tax.rate)))
                        .toList(),
                UnitOfMeasure.valueOf(entity.unitOfMeasure),
                entity.ivaExcluded);
    }
}
