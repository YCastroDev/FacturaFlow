package com.facturaflow.invoicing.application.service;

import com.facturaflow.invoicing.application.exception.CreditNoteReferenceCodeConflictException;
import com.facturaflow.invoicing.application.exception.DuplicateCreditNoteReferenceCodeException;
import com.facturaflow.invoicing.application.exception.InvoiceConcurrentModificationException;
import com.facturaflow.invoicing.application.exception.InvoiceNotFoundException;
import com.facturaflow.invoicing.application.port.in.CreateCreditNoteCommand;
import com.facturaflow.invoicing.application.port.in.CreateCreditNoteResult;
import com.facturaflow.invoicing.application.port.in.CreateCreditNoteUseCase;
import com.facturaflow.invoicing.application.port.out.CreditNoteRepository;
import com.facturaflow.invoicing.application.port.out.InvoiceRepository;
import com.facturaflow.invoicing.application.port.out.RequestHash;
import com.facturaflow.invoicing.application.port.out.StoredCreditNote;
import com.facturaflow.invoicing.domain.exception.InvalidCreditNoteException;
import com.facturaflow.invoicing.domain.model.CreditBalance;
import com.facturaflow.invoicing.domain.model.CreditNote;
import com.facturaflow.invoicing.domain.model.CreditNoteId;
import com.facturaflow.invoicing.domain.model.CreditedQuantity;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.domain.model.InvoiceId;
import com.facturaflow.invoicing.domain.model.Quantity;
import com.facturaflow.invoicing.domain.model.ReferenceCode;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Crea notas crédito (ADR 0009).
 *
 * <p><b>Idempotencia</b>, igual que en facturas (ADR 0006): código nuevo → crea la nota; código
 * existente con la misma huella → devuelve la existente; con otra huella → conflicto. Si dos
 * peticiones con el mismo código pasan la consulta inicial, el UNIQUE de la base decide.
 *
 * <p><b>La regla que cruza agregados</b> (lo acreditado nunca supera lo facturado, por línea y
 * cantidad) se valida en el dominio contra una foto: la factura y TODAS sus notas, cada una
 * leída en su propia transacción corta. El repositorio inserta la nota solo si la versión de la
 * factura sigue siendo la de la foto, y la sube en la misma transacción. Si otra nota se guardó
 * entre medio, se vuelve a leer y a validar, hasta {@value #MAX_ATTEMPTS} intentos:
 * <ul>
 *   <li>si al revalidar ya no cabe, el dominio lo rechaza (422);</li>
 *   <li>si los conflictos persisten, se propaga {@link InvoiceConcurrentModificationException} (409).</li>
 * </ul>
 */
public final class CreateCreditNoteService implements CreateCreditNoteUseCase {

    static final int MAX_ATTEMPTS = 3;

    private static final System.Logger LOG = System.getLogger(CreateCreditNoteService.class.getName());

    private final InvoiceRepository invoices;
    private final CreditNoteRepository creditNotes;
    private final Clock clock;

    public CreateCreditNoteService(InvoiceRepository invoices, CreditNoteRepository creditNotes, Clock clock) {
        this.invoices = Objects.requireNonNull(invoices, "invoices");
        this.creditNotes = Objects.requireNonNull(creditNotes, "creditNotes");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CreateCreditNoteResult create(CreateCreditNoteCommand command) {
        InvoiceId invoiceId = new InvoiceId(command.invoiceId());
        ReferenceCode referenceCode = new ReferenceCode(command.referenceCode());
        List<CreditedQuantity> requested = command.lines().stream()
                .map(line -> new CreditedQuantity(line.lineNumber(), new Quantity(line.quantity())))
                .toList();
        if (command.reason() == null) {
            throw InvalidCreditNoteException.missing("un motivo");
        }
        String observation = CreditNote.normalizedObservation(command.observation());
        RequestHash requestHash = CreditNoteRequestHasher.hash(invoiceId, command.reason(), requested, observation);

        Optional<StoredCreditNote> existing = creditNotes.findStoredByReferenceCode(referenceCode);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), requestHash);
        }

        InvoiceConcurrentModificationException lastConflict = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Invoice invoice = invoices.findById(invoiceId)
                    .orElseThrow(() -> InvoiceNotFoundException.byId(invoiceId.value()));
            CreditBalance balance = CreditBalance.of(invoice, creditNotes.findByInvoiceId(invoiceId));
            // Las reglas de negocio salen de aquí como excepción del dominio (422 o 409).
            CreditNote creditNote = CreditNote.create(CreditNoteId.generate(), referenceCode, balance,
                    command.reason(), requested, LocalDate.now(clock), observation);
            try {
                return CreateCreditNoteResult.created(
                        creditNotes.saveNew(creditNote, requestHash, invoice.version().orElseThrow()));
            } catch (InvoiceConcurrentModificationException conflict) {
                // Otra nota de la misma factura se guardó después de nuestra foto: volver a validar.
                LOG.log(Level.DEBUG, "Conflicto al crear la nota {0} sobre la factura {1} (intento {2} de {3}).",
                        referenceCode.value(), invoiceId, attempt, MAX_ATTEMPTS);
                lastConflict = conflict;
            } catch (DuplicateCreditNoteReferenceCodeException race) {
                // Otra petición con el mismo código la creó entre nuestra consulta y nuestro INSERT.
                StoredCreditNote winner = creditNotes.findStoredByReferenceCode(referenceCode).orElseThrow(() -> race);
                return replayOrConflict(winner, requestHash);
            }
        }
        LOG.log(Level.WARNING, "No se pudo crear la nota {0} sobre la factura {1}: {2} conflictos seguidos con "
                + "otras notas simultáneas.", referenceCode.value(), invoiceId, MAX_ATTEMPTS);
        throw lastConflict;
    }

    private static CreateCreditNoteResult replayOrConflict(StoredCreditNote existing, RequestHash requestHash) {
        if (existing.requestHash().equals(requestHash)) {
            return CreateCreditNoteResult.replayed(existing.creditNote());
        }
        throw new CreditNoteReferenceCodeConflictException(existing.creditNote().referenceCode().value());
    }
}
