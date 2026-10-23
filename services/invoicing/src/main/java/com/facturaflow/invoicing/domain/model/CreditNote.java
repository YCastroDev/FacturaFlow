package com.facturaflow.invoicing.domain.model;

import com.facturaflow.invoicing.domain.exception.InvalidCreditNoteException;
import com.facturaflow.invoicing.domain.exception.InvoiceNotCreditableException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Aggregate root de una nota crédito (ADR 0009). Es un agregado SEPARADO de {@link Invoice}: la
 * referencia por su {@link InvoiceId} y tiene su propio ciclo de vida PENDING -> ISSUED | REJECTED.
 * La factura no cambia al acreditarse: sigue ISSUED.
 *
 * <p>Toda nota válida nace en {@link #create}, que aplica la regla que cruza agregados con el
 * {@link CreditBalance} de la factura: solo se acreditan facturas ISSUED y lo acreditado de cada
 * línea nunca supera lo facturado. Los totales se calculan como en la factura: suma de los valores
 * ya redondeados de cada línea (ADR 0004).
 *
 * <p>El cliente no se guarda en la nota: es siempre el de la factura (ADR 0009).
 *
 * <p>{@link #version()} es un token de concurrencia opaco (ADR 0005), como en la factura.
 */
public final class CreditNote {

    public static final int MAX_OBSERVATION_LENGTH = 250;

    private final CreditNoteId id;
    private final ReferenceCode referenceCode;
    private final InvoiceId invoiceId;
    private final CreditNoteReason reason;
    private final List<CreditNoteLine> lines;
    private final LocalDate issueDate;
    private final String observation;

    private final Long version;

    private CreditNoteStatus status;
    private ElectronicDocument electronicDocument;
    private String rejectionReason;

    private CreditNote(CreditNoteId id, ReferenceCode referenceCode, InvoiceId invoiceId, CreditNoteReason reason,
                       List<CreditNoteLine> lines, LocalDate issueDate, String observation,
                       CreditNoteStatus status, ElectronicDocument electronicDocument, String rejectionReason,
                       Long version) {
        this.id = id;
        this.referenceCode = referenceCode;
        this.invoiceId = invoiceId;
        this.reason = reason;
        this.lines = lines;
        this.issueDate = issueDate;
        this.observation = observation;
        this.status = status;
        this.electronicDocument = electronicDocument;
        this.rejectionReason = rejectionReason;
        this.version = version;
    }

    /**
     * Crea una nota PENDING sobre la factura de {@code balance}.
     * <ul>
     *   <li>{@link CreditNoteReason#PARTIAL_RETURN}: acredita las cantidades pedidas, sin repetir
     *       líneas y sin superar lo que queda por acreditar de cada una;</li>
     *   <li>{@link CreditNoteReason#TOTAL_ANNULMENT}: no lleva líneas; acredita todo lo pendiente.</li>
     * </ul>
     *
     * @param balance  saldo de la factura con TODAS sus notas actuales
     * @param requested cantidades pedidas (vacía en una anulación total)
     * @throws InvoiceNotCreditableException si la factura no está ISSUED
     */
    public static CreditNote create(CreditNoteId id, ReferenceCode referenceCode, CreditBalance balance,
                                    CreditNoteReason reason, List<CreditedQuantity> requested,
                                    LocalDate issueDate, String observation) {
        requirePresent(id, "un identificador");
        requirePresent(referenceCode, "un código de referencia");
        requirePresent(balance, "el saldo de la factura que acredita");
        requirePresent(reason, "un motivo");
        requirePresent(issueDate, "una fecha de emisión");
        if (requested == null || requested.stream().anyMatch(Objects::isNull)) {
            throw InvalidCreditNoteException.missing("la lista de líneas acreditadas (vacía en una anulación)");
        }

        Invoice invoice = balance.invoice();
        if (invoice.status() != InvoiceStatus.ISSUED) {
            throw new InvoiceNotCreditableException(invoice.id().value(), invoice.status().name());
        }
        if (issueDate.isBefore(invoice.issueDate())) {
            throw InvalidCreditNoteException.issueDateBeforeInvoice(issueDate.toString(),
                    invoice.issueDate().toString());
        }

        List<CreditNoteLine> lines = switch (reason) {
            case PARTIAL_RETURN -> partialReturn(balance, requested);
            case TOTAL_ANNULMENT -> annulment(balance, requested);
        };
        return new CreditNote(id, referenceCode, invoice.id(), reason, lines, issueDate,
                normalizedObservation(observation), CreditNoteStatus.PENDING, null, null, null);
    }

    /**
     * Reconstruye una nota existente (p. ej. desde la base de datos). Valida su contenido y la
     * coherencia del estado, como {@link Invoice#reconstitute}; la regla del saldo se aplicó al crearla.
     *
     * @param version token de concurrencia opaco asignado por la persistencia (ADR 0005)
     */
    public static CreditNote reconstitute(CreditNoteId id, ReferenceCode referenceCode, InvoiceId invoiceId,
                                          CreditNoteReason reason, List<CreditNoteLine> lines,
                                          LocalDate issueDate, String observation,
                                          CreditNoteStatus status, ElectronicDocument electronicDocument,
                                          String rejectionReason, long version) {
        requirePresent(id, "un identificador");
        requirePresent(referenceCode, "un código de referencia");
        requirePresent(invoiceId, "la factura que acredita");
        requirePresent(reason, "un motivo");
        requirePresent(issueDate, "una fecha de emisión");
        requirePresent(status, "un estado");
        validateLines(lines);
        String rejection = rejectionReason == null || rejectionReason.isBlank() ? null : rejectionReason.strip();
        validateStateCoherence(status, electronicDocument, rejection);
        if (version < 0) {
            throw InvalidCreditNoteException.invalidVersion(version);
        }
        return new CreditNote(id, referenceCode, invoiceId, reason, List.copyOf(lines), issueDate,
                normalizedObservation(observation), status, electronicDocument, rejection, version);
    }

    // ----- Totales (mismas reglas que la factura) -----

    public Money subtotal() {
        return sum(InvoiceLine::grossAmount);
    }

    public Money totalDiscount() {
        return sum(InvoiceLine::discountAmount);
    }

    public Money totalTax() {
        return sum(InvoiceLine::taxAmount);
    }

    public Money totalTax(TaxType type) {
        if (type == null) {
            throw InvalidCreditNoteException.missing("un tipo de impuesto para el desglose");
        }
        return sum(line -> line.taxAmount(type));
    }

    public Money total() {
        return subtotal().minus(totalDiscount()).plus(totalTax());
    }

    // ----- Máquina de estados -----

    public void markAsIssued(ElectronicDocument document) {
        ensureCanTransitionTo(CreditNoteStatus.ISSUED);
        if (document == null) {
            throw InvalidCreditNoteException.missing("un documento electrónico para marcarse como emitida");
        }
        this.electronicDocument = document;
        this.status = CreditNoteStatus.ISSUED;
    }

    public void markAsRejected(String reason) {
        ensureCanTransitionTo(CreditNoteStatus.REJECTED);
        if (reason == null || reason.isBlank()) {
            throw InvalidCreditNoteException.missingRejectionReason();
        }
        this.rejectionReason = reason.strip();
        this.status = CreditNoteStatus.REJECTED;
    }

    /** Si el total oficial (proveedor) difiere del calculado. No impide la emisión, pero debe investigarse. */
    public boolean hasOfficialTotalDiscrepancy() {
        return electronicDocument != null && !electronicDocument.officialTotal().equals(total());
    }

    // ----- Consultas -----

    public CreditNoteId id() {
        return id;
    }

    public ReferenceCode referenceCode() {
        return referenceCode;
    }

    public InvoiceId invoiceId() {
        return invoiceId;
    }

    public CreditNoteReason reason() {
        return reason;
    }

    /** Lista inmutable, ordenada por número de línea de la factura. */
    public List<CreditNoteLine> lines() {
        return lines;
    }

    public LocalDate issueDate() {
        return issueDate;
    }

    public Optional<String> observation() {
        return Optional.ofNullable(observation);
    }

    public CreditNoteStatus status() {
        return status;
    }

    public Optional<ElectronicDocument> electronicDocument() {
        return Optional.ofNullable(electronicDocument);
    }

    public Optional<String> rejectionReason() {
        return Optional.ofNullable(rejectionReason);
    }

    /** Token de concurrencia opaco; vacío si la nota nunca se ha guardado. */
    public Optional<Long> version() {
        return Optional.ofNullable(version);
    }

    // ----- Identidad -----

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof CreditNote note && id.equals(note.id));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "CreditNote[id=" + id + ", referenceCode=" + referenceCode.value() + ", invoiceId=" + invoiceId
                + ", status=" + status + "]";
    }

    // ----- Internos -----

    private static List<CreditNoteLine> partialReturn(CreditBalance balance, List<CreditedQuantity> requested) {
        if (requested.isEmpty()) {
            throw InvalidCreditNoteException.noLines();
        }
        Set<Integer> seen = new HashSet<>();
        List<CreditNoteLine> lines = new ArrayList<>();
        for (CreditedQuantity credit : requested) {
            int lineNumber = credit.invoiceLineNumber();
            if (!seen.add(lineNumber)) {
                throw InvalidCreditNoteException.duplicateLine(lineNumber);
            }
            CreditBalance.LineBalance line = balance.line(lineNumber);
            if (credit.quantity().value().compareTo(line.remaining()) > 0) {
                throw InvalidCreditNoteException.exceedsRemaining(lineNumber, credit.quantity().value(),
                        line.remaining());
            }
            lines.add(new CreditNoteLine(lineNumber, balance.invoiceLine(lineNumber).withQuantity(credit.quantity())));
        }
        lines.sort(Comparator.comparingInt(CreditNoteLine::invoiceLineNumber));
        return List.copyOf(lines);
    }

    private static List<CreditNoteLine> annulment(CreditBalance balance, List<CreditedQuantity> requested) {
        if (!requested.isEmpty()) {
            throw InvalidCreditNoteException.annulmentWithLines();
        }
        List<CreditNoteLine> lines = balance.lines().stream()
                .filter(CreditBalance.LineBalance::hasRemaining)
                .map(line -> new CreditNoteLine(line.lineNumber(),
                        balance.invoiceLine(line.lineNumber()).withQuantity(new Quantity(line.remaining()))))
                .toList();
        if (lines.isEmpty()) {
            throw InvalidCreditNoteException.nothingToCredit(balance.invoice().id().value());
        }
        return lines;
    }

    private static void validateLines(List<CreditNoteLine> lines) {
        if (lines == null || lines.isEmpty()) {
            throw InvalidCreditNoteException.noLines();
        }
        if (lines.stream().anyMatch(Objects::isNull)) {
            throw InvalidCreditNoteException.missing("líneas no nulas");
        }
        Set<Integer> seen = new HashSet<>();
        for (CreditNoteLine line : lines) {
            if (!seen.add(line.invoiceLineNumber())) {
                throw InvalidCreditNoteException.duplicateLine(line.invoiceLineNumber());
            }
        }
    }

    private static void validateStateCoherence(CreditNoteStatus status, ElectronicDocument document, String reason) {
        switch (status) {
            case PENDING -> {
                if (document != null) {
                    throw InvalidCreditNoteException.inconsistentState("PENDING", "no puede tener documento electrónico");
                }
                if (reason != null) {
                    throw InvalidCreditNoteException.inconsistentState("PENDING", "no puede tener motivo de rechazo");
                }
            }
            case ISSUED -> {
                if (document == null) {
                    throw InvalidCreditNoteException.inconsistentState("ISSUED", "requiere documento electrónico");
                }
                if (reason != null) {
                    throw InvalidCreditNoteException.inconsistentState("ISSUED", "no puede tener motivo de rechazo");
                }
            }
            case REJECTED -> {
                if (reason == null) {
                    throw InvalidCreditNoteException.inconsistentState("REJECTED", "requiere motivo de rechazo");
                }
                if (document != null) {
                    throw InvalidCreditNoteException.inconsistentState("REJECTED",
                            "no puede tener documento electrónico");
                }
            }
        }
    }

    private void ensureCanTransitionTo(CreditNoteStatus target) {
        if (!status.canTransitionTo(target)) {
            throw InvalidCreditNoteException.invalidTransition(id.value(), status.name(), target.name());
        }
    }

    private Money sum(Function<InvoiceLine, Money> amount) {
        return lines.stream().map(CreditNoteLine::creditedLine).map(amount).reduce(Money.ZERO, Money::plus);
    }

    /**
     * Observación tal como la guardaría la nota: sin espacios en los extremos y {@code null} si está
     * vacía. Pública para que la huella de la petición (idempotencia) normalice con la misma regla.
     */
    public static String normalizedObservation(String observation) {
        if (observation == null || observation.isBlank()) {
            return null;
        }
        String stripped = observation.strip();
        if (stripped.length() > MAX_OBSERVATION_LENGTH) {
            throw InvalidCreditNoteException.observationTooLong(stripped.length(), MAX_OBSERVATION_LENGTH);
        }
        return stripped;
    }

    private static void requirePresent(Object value, String description) {
        if (value == null) {
            throw InvalidCreditNoteException.missing(description);
        }
    }
}
