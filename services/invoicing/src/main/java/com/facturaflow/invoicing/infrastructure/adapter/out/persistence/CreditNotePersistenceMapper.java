package com.facturaflow.invoicing.infrastructure.adapter.out.persistence;

import com.facturaflow.invoicing.application.port.out.RequestHash;
import com.facturaflow.invoicing.application.port.out.StoredCreditNote;
import com.facturaflow.invoicing.domain.model.CreditNote;
import com.facturaflow.invoicing.domain.model.CreditNoteId;
import com.facturaflow.invoicing.domain.model.CreditNoteLine;
import com.facturaflow.invoicing.domain.model.CreditNoteReason;
import com.facturaflow.invoicing.domain.model.CreditNoteStatus;
import com.facturaflow.invoicing.domain.model.DiscountRate;
import com.facturaflow.invoicing.domain.model.ElectronicDocument;
import com.facturaflow.invoicing.domain.model.InvoiceId;
import com.facturaflow.invoicing.domain.model.InvoiceLine;
import com.facturaflow.invoicing.domain.model.LineTax;
import com.facturaflow.invoicing.domain.model.Money;
import com.facturaflow.invoicing.domain.model.Quantity;
import com.facturaflow.invoicing.domain.model.ReferenceCode;
import com.facturaflow.invoicing.domain.model.TaxRate;
import com.facturaflow.invoicing.domain.model.TaxType;
import com.facturaflow.invoicing.domain.model.UnitOfMeasure;
import java.net.URI;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Traducción a mano entre el agregado {@link CreditNote} y sus entidades JPA, con los mismos
 * criterios que {@link InvoicePersistenceMapper}: enums por su nombre, líneas inmutables y
 * reconstrucción siempre a través de {@link CreditNote#reconstitute}.
 */
@Component
class CreditNotePersistenceMapper {

    /** Entidad para una nota que aún no existe en la base (versión vacía: JPA la inicializa). */
    CreditNoteEntity toNewEntity(CreditNote creditNote, RequestHash requestHash) {
        CreditNoteEntity entity = new CreditNoteEntity();
        entity.id = creditNote.id().value();
        entity.requestHash = requestHash.value();
        entity.referenceCode = creditNote.referenceCode().value();
        entity.invoiceId = creditNote.invoiceId().value();
        entity.reason = creditNote.reason().name();
        entity.issueDate = creditNote.issueDate();
        entity.observation = creditNote.observation().orElse(null);
        for (CreditNoteLine line : creditNote.lines()) {
            entity.lines.add(toLineEntity(line));
        }
        applyMutableState(creditNote, entity);
        return entity;
    }

    /** Copia lo único que cambia después de crear una nota: estado, documento electrónico y motivo de rechazo. */
    void applyMutableState(CreditNote creditNote, CreditNoteEntity entity) {
        entity.status = creditNote.status().name();
        ElectronicDocument document = creditNote.electronicDocument().orElse(null);
        entity.edocNumber = document == null ? null : document.number();
        entity.edocUniqueCode = document == null ? null : document.uniqueCode();
        entity.edocQrUrl = document == null ? null : document.qrUrl().toString();
        entity.edocPublicUrl = document == null ? null : document.publicUrl().toString();
        // TIMESTAMPTZ guarda microsegundos: mismo truncamiento que en las facturas.
        entity.edocValidatedAt = document == null ? null : document.validatedAt().truncatedTo(ChronoUnit.MICROS);
        entity.edocOfficialTotal = document == null ? null : document.officialTotal().amount();
        entity.rejectionReason = creditNote.rejectionReason().orElse(null);
    }

    StoredCreditNote toStored(CreditNoteEntity entity) {
        return new StoredCreditNote(toDomain(entity), new RequestHash(entity.requestHash));
    }

    CreditNote toDomain(CreditNoteEntity entity) {
        ElectronicDocument document = entity.edocNumber == null ? null : new ElectronicDocument(
                entity.edocNumber, entity.edocUniqueCode, URI.create(entity.edocQrUrl),
                URI.create(entity.edocPublicUrl), entity.edocValidatedAt, Money.of(entity.edocOfficialTotal));
        return CreditNote.reconstitute(
                new CreditNoteId(entity.id),
                new ReferenceCode(entity.referenceCode),
                new InvoiceId(entity.invoiceId),
                CreditNoteReason.valueOf(entity.reason),
                entity.lines.stream().map(this::toDomainLine).toList(),
                entity.issueDate,
                entity.observation,
                CreditNoteStatus.valueOf(entity.status),
                document,
                entity.rejectionReason,
                entity.version);
    }

    private CreditNoteLineEntity toLineEntity(CreditNoteLine line) {
        InvoiceLine credited = line.creditedLine();
        CreditNoteLineEntity entity = new CreditNoteLineEntity();
        entity.id = UUID.randomUUID();
        entity.invoiceLineNumber = line.invoiceLineNumber();
        entity.code = credited.code();
        entity.name = credited.name();
        entity.quantity = credited.quantity().value();
        entity.unitPrice = credited.unitPrice().amount();
        entity.discountRate = credited.discountRate().percent();
        entity.unitOfMeasure = credited.unit().name();
        entity.ivaExcluded = credited.excluded();
        for (LineTax tax : credited.taxes()) {
            entity.taxes.add(new LineTaxEmbeddable(tax.type().name(), tax.rate().percent()));
        }
        return entity;
    }

    private CreditNoteLine toDomainLine(CreditNoteLineEntity entity) {
        return new CreditNoteLine(entity.invoiceLineNumber, new InvoiceLine(
                entity.code,
                entity.name,
                new Quantity(entity.quantity),
                Money.of(entity.unitPrice),
                new DiscountRate(entity.discountRate),
                entity.taxes.stream()
                        .map(tax -> new LineTax(TaxType.valueOf(tax.taxType), new TaxRate(tax.rate)))
                        .toList(),
                UnitOfMeasure.valueOf(entity.unitOfMeasure),
                entity.ivaExcluded));
    }
}
