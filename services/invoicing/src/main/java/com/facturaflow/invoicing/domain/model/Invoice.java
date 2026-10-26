package com.facturaflow.invoicing.domain.model;

import com.facturaflow.invoicing.domain.exception.InvalidInvoiceException;
import com.facturaflow.invoicing.domain.exception.InvalidInvoiceStateTransitionException;
import com.facturaflow.invoicing.domain.exception.InvalidPaymentTermsException;
import java.time.LocalDate;
import java.util.ArrayList;
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
 *
 * <p>{@link #version()} es un token de concurrencia opaco (ADR 0005): el dominio no lo
 * interpreta, solo lo transporta para que la persistencia detecte modificaciones
 * concurrentes. Vacío en una factura nueva; lo asigna el repositorio al guardar.
 *
 * <p>Eventos de dominio (ADR 0015): {@link #markAsIssued} y {@link #markAsRejected} registran
 * {@link InvoiceEvent.InvoiceIssued} o {@link InvoiceEvent.InvoiceRejected} en {@link #pendingEvents()}; el
 * repositorio los guarda en el outbox en la MISMA transacción que el cambio de estado. Una factura recién creada
 * o reconstituida no trae eventos, así que la instancia que devuelve el repositorio no los repite.
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

    private final Long version;

    private InvoiceStatus status;
    private ElectronicDocument electronicDocument;
    private String rejectionReason;
    private final List<InvoiceEvent> pendingEvents = new ArrayList<>();

    private Invoice(InvoiceId id, ReferenceCode referenceCode, CustomerSnapshot customer, List<InvoiceLine> lines,
                    PaymentMethod paymentMethod, PaymentTerms paymentTerms, LocalDate issueDate, String observation,
                    InvoiceStatus status, ElectronicDocument electronicDocument, String rejectionReason, Long version) {
        this.id = id;
        this.referenceCode = referenceCode;
        this.customer = customer;
        this.lines = lines;
        this.paymentMethod = paymentMethod;
        this.paymentTerms = paymentTerms;
        this.issueDate = issueDate;
        this.observation = observation;
        this.status = status;
        this.electronicDocument = electronicDocument;
        this.rejectionReason = rejectionReason;
        this.version = version;
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
        validateContent(id, referenceCode, customer, lines, paymentMethod, paymentTerms, issueDate);
        return new Invoice(id, referenceCode, customer, List.copyOf(lines), paymentMethod, paymentTerms, issueDate,
                normalizedObservation(observation), InvoiceStatus.PENDING, null, null, null);
    }

    /**
     * Reconstruye una factura ya existente (p. ej. desde la base de datos) con su estado
     * completo. Aplica las mismas reglas que {@link #create} y además exige que el estado
     * sea coherente: PENDING sin documento ni motivo; ISSUED con documento electrónico;
     * REJECTED con motivo de rechazo.
     *
     * @param version token de concurrencia opaco asignado por la persistencia (ADR 0005)
     */
    public static Invoice reconstitute(InvoiceId id, ReferenceCode referenceCode, CustomerSnapshot customer,
                                       List<InvoiceLine> lines, PaymentMethod paymentMethod,
                                       PaymentTerms paymentTerms, LocalDate issueDate, String observation,
                                       InvoiceStatus status, ElectronicDocument electronicDocument,
                                       String rejectionReason, long version) {
        validateContent(id, referenceCode, customer, lines, paymentMethod, paymentTerms, issueDate);
        requirePresent(status, "un estado");
        String reason = rejectionReason == null || rejectionReason.isBlank() ? null : rejectionReason.strip();
        validateStateCoherence(status, electronicDocument, reason);
        if (version < 0) {
            throw InvalidInvoiceException.invalidVersion(version);
        }
        return new Invoice(id, referenceCode, customer, List.copyOf(lines), paymentMethod, paymentTerms, issueDate,
                normalizedObservation(observation), status, electronicDocument, reason, version);
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

    /** Desglose de impuestos por tipo (p. ej. total de IVA y total de INC). */
    public Money totalTax(TaxType type) {
        if (type == null) {
            throw InvalidInvoiceException.missing("un tipo de impuesto para el desglose");
        }
        return sum(line -> line.taxAmount(type));
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
        pendingEvents.add(new InvoiceEvent.InvoiceIssued(id));
    }

    public void markAsRejected(String reason) {
        ensureCanTransitionTo(InvoiceStatus.REJECTED);
        if (reason == null || reason.isBlank()) {
            throw InvalidInvoiceException.missingRejectionReason();
        }
        this.rejectionReason = reason.strip();
        this.status = InvoiceStatus.REJECTED;
        pendingEvents.add(new InvoiceEvent.InvoiceRejected(id));
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

    /** Eventos registrados por esta instancia y aún no guardados (copia inmutable). */
    public List<InvoiceEvent> pendingEvents() {
        return List.copyOf(pendingEvents);
    }

    /** Token de concurrencia opaco; vacío si la factura nunca se ha guardado. */
    public Optional<Long> version() {
        return Optional.ofNullable(version);
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

    private static void validateContent(InvoiceId id, ReferenceCode referenceCode, CustomerSnapshot customer,
                                        List<InvoiceLine> lines, PaymentMethod paymentMethod,
                                        PaymentTerms paymentTerms, LocalDate issueDate) {
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
    }

    private static void validateStateCoherence(InvoiceStatus status, ElectronicDocument document, String reason) {
        switch (status) {
            case PENDING -> {
                if (document != null) {
                    throw InvalidInvoiceException.inconsistentState("PENDING", "no puede tener documento electrónico");
                }
                if (reason != null) {
                    throw InvalidInvoiceException.inconsistentState("PENDING", "no puede tener motivo de rechazo");
                }
            }
            case ISSUED -> {
                if (document == null) {
                    throw InvalidInvoiceException.inconsistentState("ISSUED", "requiere documento electrónico");
                }
                if (reason != null) {
                    throw InvalidInvoiceException.inconsistentState("ISSUED", "no puede tener motivo de rechazo");
                }
            }
            case REJECTED -> {
                if (reason == null) {
                    throw InvalidInvoiceException.inconsistentState("REJECTED", "requiere motivo de rechazo");
                }
                if (document != null) {
                    throw InvalidInvoiceException.inconsistentState("REJECTED", "no puede tener documento electrónico");
                }
            }
        }
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

    /**
     * Observación tal como la guardaría la factura: sin espacios en los extremos y
     * {@code null} si está vacía. Pública para que quien compare peticiones (idempotencia,
     * ADR 0006) normalice con la misma regla que el dominio.
     *
     * @throws InvalidInvoiceException si supera {@value #MAX_OBSERVATION_LENGTH} caracteres
     */
    public static String normalizedObservation(String observation) {
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
