package com.facturaflow.invoicing.domain.model;

import com.facturaflow.invoicing.domain.exception.InvalidInvoiceException;
import com.facturaflow.invoicing.domain.exception.InvalidInvoiceStateTransitionException;
import com.facturaflow.invoicing.domain.exception.InvalidPaymentTermsException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Aggregate root de facturación. Toda factura válida nace en {@link #create}; no hay
 * setters, y los únicos cambios posibles son las transiciones de estado.
 *
 * <p>Los totales se calculan sumando los valores ya redondeados de cada línea
 * (ADR 0004). Al emitirse, se guarda además el total oficial devuelto por el
 * proveedor, que es el que tiene validez legal.
 */
public final class Invoice {

    public static final int MAX_OBSERVATION_LENGTH = 250;

    private final InvoiceId id;
    private final ReferenceCode referenceCode;
    private final CustomerSnapshot customer;
    private final List<InvoiceLine> lines;
    private final PaymentMethod paymentMethod;
    private final PaymentTerms paymentTerms;
    private final LocalDate issueDate;
    private final String observation;

    private InvoiceStatus status;
    private ElectronicDocument electronicDocument;
    private String rejectionReason;

    private Invoice(InvoiceId id, ReferenceCode referenceCode, CustomerSnapshot customer, List<InvoiceLine> lines,
                    PaymentMethod paymentMethod, PaymentTerms paymentTerms, LocalDate issueDate, String observation) {
        this.id = id;
        this.referenceCode = referenceCode;
        this.customer = customer;
        this.lines = lines;
        this.paymentMethod = paymentMethod;
        this.paymentTerms = paymentTerms;
        this.issueDate = issueDate;
        this.observation = observation;
        this.status = InvoiceStatus.PENDING;
    }

    /**
     * Crea una factura PENDING validando todas las reglas de negocio. Las fechas
     * entran como parámetro: el dominio nunca consulta el reloj del sistema.
     *
     * @param observation opcional (puede ser {@code null}); máximo {@value #MAX_OBSERVATION_LENGTH} caracteres
     */
    public static Invoice create(InvoiceId id, ReferenceCode referenceCode, CustomerSnapshot customer,
                                 List<InvoiceLine> lines, PaymentMethod paymentMethod, PaymentTerms paymentTerms,
                                 LocalDate issueDate, String observation) {
        requirePresent(id, "un identificador");
        requirePresent(referenceCode, "un código de referencia");
        requirePresent(customer, "un cliente");
        requirePresent(paymentMethod, "un medio de pago");
        requirePresent(paymentTerms, "una forma de pago");
        requirePresent(issueDate, "una fecha de emisión");
        if (lines == null || lines.isEmpty()) {
            throw InvalidInvoiceException.noLines();
        }
        if (lines.stream().anyMatch(Objects::isNull)) {
            throw InvalidInvoiceException.missing("líneas no nulas");
        }
        validatePaymentTerms(paymentTerms, issueDate);
        return new Invoice(id, referenceCode, customer, List.copyOf(lines), paymentMethod, paymentTerms, issueDate,
                normalizeObservation(observation));
    }

    // ----- Totales (suma de valores ya redondeados por línea) -----

    /** Suma de los valores brutos (cantidad x precio), antes de descuentos e impuestos. */
    public Money subtotal() {
        return sum(InvoiceLine::grossAmount);
    }

    public Money totalDiscount() {
        return sum(InvoiceLine::discountAmount);
    }

    public Money totalTax() {
        return sum(InvoiceLine::taxAmount);
    }

    /** subtotal - descuentos + impuestos. Las retenciones, cuando existan, no modifican este valor. */
    public Money total() {
        return subtotal().minus(totalDiscount()).plus(totalTax());
    }

    // ----- Máquina de estados -----

    public void markAsIssued(ElectronicDocument document) {
        ensureCanTransitionTo(InvoiceStatus.ISSUED);
        if (document == null) {
            throw InvalidInvoiceException.missing("un documento electrónico para marcarse como emitida");
        }
        this.electronicDocument = document;
        this.status = InvoiceStatus.ISSUED;
    }

    public void markAsRejected(String reason) {
        ensureCanTransitionTo(InvoiceStatus.REJECTED);
        if (reason == null || reason.isBlank()) {
            throw InvalidInvoiceException.missingRejectionReason();
        }
        this.rejectionReason = reason.strip();
        this.status = InvoiceStatus.REJECTED;
    }

    /**
     * Indica si el total oficial (proveedor) difiere del calculado por el dominio.
     * No impide la emisión: el oficial es el legal, pero la diferencia debe investigarse.
     */
    public boolean hasOfficialTotalDiscrepancy() {
        return electronicDocument != null && !electronicDocument.officialTotal().equals(total());
    }

    // ----- Consultas -----

    public InvoiceId id() {
        return id;
    }

    public ReferenceCode referenceCode() {
        return referenceCode;
    }

    public CustomerSnapshot customer() {
        return customer;
    }

    /** Lista inmutable. */
    public List<InvoiceLine> lines() {
        return lines;
    }

    public PaymentMethod paymentMethod() {
        return paymentMethod;
    }

    public PaymentTerms paymentTerms() {
        return paymentTerms;
    }

    public LocalDate issueDate() {
        return issueDate;
    }

    public Optional<String> observation() {
        return Optional.ofNullable(observation);
    }

    public InvoiceStatus status() {
        return status;
    }

    public Optional<ElectronicDocument> electronicDocument() {
        return Optional.ofNullable(electronicDocument);
    }

    public Optional<String> rejectionReason() {
        return Optional.ofNullable(rejectionReason);
    }

    // ----- Identidad -----

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof Invoice invoice && id.equals(invoice.id));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Invoice[id=" + id + ", referenceCode=" + referenceCode.value() + ", status=" + status + "]";
    }

    // ----- Internos -----

    private void ensureCanTransitionTo(InvoiceStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidInvoiceStateTransitionException(id.value(), status.name(), target.name());
        }
    }

    private Money sum(Function<InvoiceLine, Money> amount) {
        return lines.stream().map(amount).reduce(Money.ZERO, Money::plus);
    }

    private static void validatePaymentTerms(PaymentTerms paymentTerms, LocalDate issueDate) {
        switch (paymentTerms) {
            case PaymentTerms.Cash _ -> {
            }
            case PaymentTerms.Credit(LocalDate dueDate) -> {
                if (!dueDate.isAfter(issueDate)) {
                    throw InvalidPaymentTermsException.dueDateNotAfterIssueDate(dueDate.toString(),
                            issueDate.toString());
                }
            }
        }
    }

    private static String normalizeObservation(String observation) {
        if (observation == null || observation.isBlank()) {
            return null;
        }
        String stripped = observation.strip();
        if (stripped.length() > MAX_OBSERVATION_LENGTH) {
            throw InvalidInvoiceException.observationTooLong(stripped.length(), MAX_OBSERVATION_LENGTH);
        }
        return stripped;
    }

    private static void requirePresent(Object value, String description) {
        if (value == null) {
            throw InvalidInvoiceException.missing(description);
        }
    }
}
