package com.facturaflow.sales.domain.model;

import com.facturaflow.sales.domain.exception.InvalidPaymentTermsException;
import com.facturaflow.sales.domain.exception.InvalidSaleException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Venta confirmada: el aggregate de sales (ADR 0012). Se crea completa en una sola petición y nace
 * {@link SaleStatus#CONFIRMED} (no hay carrito). Guarda una COPIA de lo que devolvió catalog (cliente y
 * productos) más lo que pidió el vendedor (cantidades, descuentos, pago), así que tiene todo lo que
 * invoicing necesitará para facturarla sin volver a consultar a catalog.
 *
 * <p>Reglas: de 1 a {@link #MAX_LINES} líneas; a crédito, el vencimiento es posterior a la fecha de la
 * venta; observación opcional de hasta {@link #MAX_OBSERVATION_LENGTH} caracteres. Los totales son la
 * suma de los valores ya redondeados por línea (el mismo criterio que la factura en invoicing).
 *
 * <p>{@link #version()} es un token opaco del adaptador de persistencia (como en invoicing): vacío
 * hasta que se guarda.
 */
public final class Sale {

    /** Coincide con el tope de la consulta por lote de catalog (una sola llamada por venta). */
    public static final int MAX_LINES = 100;
    public static final int MAX_OBSERVATION_LENGTH = 250;

    private final SaleId id;
    private final ReferenceCode referenceCode;
    private final LocalDate saleDate;
    private final Instant createdAt;
    private final CustomerSnapshot customer;
    private final List<SaleLine> lines;
    private final PaymentMethod paymentMethod;
    private final PaymentTerms paymentTerms;
    private final String observation;
    private final SaleStatus status;
    private final Long version;

    private Sale(SaleId id, ReferenceCode referenceCode, LocalDate saleDate, Instant createdAt,
                 CustomerSnapshot customer, List<SaleLine> lines, PaymentMethod paymentMethod,
                 PaymentTerms paymentTerms, String observation, SaleStatus status, Long version) {
        this.id = id;
        this.referenceCode = referenceCode;
        this.saleDate = saleDate;
        this.createdAt = createdAt;
        this.customer = customer;
        this.lines = lines;
        this.paymentMethod = paymentMethod;
        this.paymentTerms = paymentTerms;
        this.observation = observation;
        this.status = status;
        this.version = version;
    }

    /**
     * Confirma una venta nueva.
     *
     * @param saleDate  fecha de la venta en Colombia (la pone el caso de uso con su reloj)
     * @param createdAt instante de creación (para ordenar el listado)
     */
    public static Sale confirm(SaleId id, ReferenceCode referenceCode, LocalDate saleDate, Instant createdAt,
                               CustomerSnapshot customer, List<SaleLine> lines, PaymentMethod paymentMethod,
                               PaymentTerms paymentTerms, String observation) {
        validate(id, referenceCode, saleDate, createdAt, customer, lines, paymentMethod, paymentTerms);
        if (paymentTerms instanceof PaymentTerms.Credit credit && !credit.dueDate().isAfter(saleDate)) {
            throw InvalidPaymentTermsException.dueDateNotAfterSaleDate(credit.dueDate().toString(), saleDate.toString());
        }
        return new Sale(id, referenceCode, saleDate, createdAt, customer, List.copyOf(lines), paymentMethod,
                paymentTerms, normalizeObservation(observation), SaleStatus.CONFIRMED, null);
    }

    /**
     * Reconstruye una venta guardada, con las mismas validaciones salvo la del vencimiento (que se
     * comprobó contra la fecha de la venta al confirmarla y no depende de "hoy").
     */
    public static Sale reconstitute(SaleId id, ReferenceCode referenceCode, LocalDate saleDate, Instant createdAt,
                                    CustomerSnapshot customer, List<SaleLine> lines, PaymentMethod paymentMethod,
                                    PaymentTerms paymentTerms, String observation, SaleStatus status, long version) {
        validate(id, referenceCode, saleDate, createdAt, customer, lines, paymentMethod, paymentTerms);
        if (status == null) {
            throw InvalidSaleException.missing("un estado");
        }
        return new Sale(id, referenceCode, saleDate, createdAt, customer, List.copyOf(lines), paymentMethod,
                paymentTerms, normalizeObservation(observation), status, version);
    }

    private static void validate(SaleId id, ReferenceCode referenceCode, LocalDate saleDate, Instant createdAt,
                                 CustomerSnapshot customer, List<SaleLine> lines, PaymentMethod paymentMethod,
                                 PaymentTerms paymentTerms) {
        require(id, "un id");
        require(referenceCode, "un código de referencia");
        require(saleDate, "una fecha");
        require(createdAt, "un instante de creación");
        require(customer, "los datos del cliente");
        require(paymentMethod, "un medio de pago");
        require(paymentTerms, "una forma de pago");
        if (lines == null || lines.isEmpty() || lines.size() > MAX_LINES) {
            throw InvalidSaleException.lineCount(lines == null ? 0 : lines.size(), MAX_LINES);
        }
        if (lines.stream().anyMatch(Objects::isNull)) {
            throw InvalidSaleException.missing("líneas no nulas");
        }
    }

    /** Sin espacios a los lados; vacía = sin observación. Pública: la huella de la petición la usa igual. */
    public static String normalizeObservation(String observation) {
        if (observation == null || observation.isBlank()) {
            return null;
        }
        String stripped = observation.strip();
        if (stripped.length() > MAX_OBSERVATION_LENGTH) {
            throw InvalidSaleException.observationTooLong(stripped.length(), MAX_OBSERVATION_LENGTH);
        }
        return stripped;
    }

    private static void require(Object value, String field) {
        if (value == null) {
            throw InvalidSaleException.missing(field);
        }
    }

    // ----- Totales (suma de valores ya redondeados por línea, como la factura) -----

    /** Suma de los valores brutos (cantidad x precio), antes de descuentos e impuestos. */
    public Money subtotal() {
        return sum(SaleLine::grossAmount);
    }

    public Money totalDiscount() {
        return sum(SaleLine::discountAmount);
    }

    public Money totalTax() {
        return sum(SaleLine::taxAmount);
    }

    /** Desglose de impuestos por tipo (p. ej. total de IVA y total de INC). */
    public Money totalTax(TaxType type) {
        if (type == null) {
            throw InvalidSaleException.missing("un tipo de impuesto para el desglose");
        }
        return sum(line -> line.taxAmount(type));
    }

    /** subtotal - descuentos + impuestos. */
    public Money total() {
        return subtotal().minus(totalDiscount()).plus(totalTax());
    }

    private Money sum(Function<SaleLine, Money> amount) {
        return lines.stream().map(amount).reduce(Money.ZERO, Money::plus);
    }

    // ----- Lectura -----

    public SaleId id() {
        return id;
    }

    public ReferenceCode referenceCode() {
        return referenceCode;
    }

    public LocalDate saleDate() {
        return saleDate;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public CustomerSnapshot customer() {
        return customer;
    }

    public List<SaleLine> lines() {
        return lines;
    }

    public PaymentMethod paymentMethod() {
        return paymentMethod;
    }

    public PaymentTerms paymentTerms() {
        return paymentTerms;
    }

    public Optional<String> observation() {
        return Optional.ofNullable(observation);
    }

    public SaleStatus status() {
        return status;
    }

    public Optional<Long> version() {
        return Optional.ofNullable(version);
    }
}
