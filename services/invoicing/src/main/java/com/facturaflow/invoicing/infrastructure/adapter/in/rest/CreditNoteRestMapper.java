package com.facturaflow.invoicing.infrastructure.adapter.in.rest;

import static com.facturaflow.invoicing.infrastructure.adapter.in.rest.InvoiceRestMapper.amount;

import com.facturaflow.invoicing.application.port.in.CreateCreditNoteCommand;
import com.facturaflow.invoicing.domain.model.CreditNote;
import com.facturaflow.invoicing.domain.model.CreditNoteLine;
import com.facturaflow.invoicing.domain.model.InvoiceLine;
import com.facturaflow.invoicing.domain.model.TaxType;
import com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto.ApiCreditNoteReason;
import com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto.ApiCreditNoteStatus;
import com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto.ApiTaxType;
import com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto.ApiUnitOfMeasure;
import com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto.CreateCreditNoteRequest;
import com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto.CreditNoteResponse;
import com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto.InvoiceResponse;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Traducción entre los DTOs HTTP de notas crédito y los comandos/resultados de los casos de uso,
 * con los mismos criterios que {@link InvoiceRestMapper}: la respuesta se construye LEYENDO el agregado.
 */
final class CreditNoteRestMapper {

    private CreditNoteRestMapper() {
    }

    static CreateCreditNoteCommand toCommand(UUID invoiceId, CreateCreditNoteRequest request) {
        List<CreateCreditNoteCommand.Line> lines = request.lines() == null ? List.of() : request.lines().stream()
                .map(line -> new CreateCreditNoteCommand.Line(line.lineNumber(), line.quantity()))
                .toList();
        return new CreateCreditNoteCommand(invoiceId, request.referenceCode(), request.reason().toDomain(), lines,
                request.observation());
    }

    static CreditNoteResponse toResponse(CreditNote creditNote) {
        return new CreditNoteResponse(
                creditNote.id().value(),
                creditNote.referenceCode().value(),
                creditNote.invoiceId().value(),
                ApiCreditNoteReason.from(creditNote.reason()),
                ApiCreditNoteStatus.from(creditNote.status()),
                creditNote.issueDate(),
                creditNote.observation().orElse(null),
                creditNote.lines().stream().map(CreditNoteRestMapper::toResponseLine).toList(),
                toTotals(creditNote),
                creditNote.electronicDocument()
                        .map(document -> new CreditNoteResponse.ElectronicDocument(
                                document.number(),
                                document.uniqueCode(),
                                document.qrUrl().toString(),
                                document.publicUrl().toString(),
                                document.validatedAt(),
                                amount(document.officialTotal()),
                                creditNote.hasOfficialTotalDiscrepancy()))
                        .orElse(null),
                creditNote.rejectionReason().orElse(null));
    }

    private static CreditNoteResponse.Line toResponseLine(CreditNoteLine creditNoteLine) {
        InvoiceLine line = creditNoteLine.creditedLine();
        return new CreditNoteResponse.Line(
                creditNoteLine.invoiceLineNumber(),
                line.code(),
                line.name(),
                line.quantity().value(),
                line.unitPrice().amount(),
                line.discountRate().percent(),
                ApiUnitOfMeasure.from(line.unit()),
                line.excluded(),
                InvoiceRestMapper.toLineTaxes(line),
                amount(line.grossAmount()),
                amount(line.discountAmount()),
                amount(line.taxableAmount()),
                amount(line.taxAmount()),
                amount(line.total()));
    }

    /** Mismo desglose que la factura: solo los tipos de impuesto presentes, en el orden del enum. */
    private static InvoiceResponse.Totals toTotals(CreditNote creditNote) {
        List<InvoiceResponse.TaxTotal> taxes = Arrays.stream(TaxType.values())
                .filter(type -> creditNote.lines().stream()
                        .anyMatch(line -> line.creditedLine().taxes().stream().anyMatch(tax -> tax.type() == type)))
                .map(type -> new InvoiceResponse.TaxTotal(ApiTaxType.from(type), amount(creditNote.totalTax(type))))
                .toList();
        return new InvoiceResponse.Totals(
                amount(creditNote.subtotal()),
                amount(creditNote.totalDiscount()),
                taxes,
                amount(creditNote.totalTax()),
                amount(creditNote.total()));
    }
}
