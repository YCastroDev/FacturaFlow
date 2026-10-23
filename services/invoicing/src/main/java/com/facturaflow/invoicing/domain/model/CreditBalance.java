package com.facturaflow.invoicing.domain.model;

import com.facturaflow.invoicing.domain.exception.InvalidCreditNoteException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.IntStream;

/**
 * Saldo acreditable de una factura: la regla que cruza los agregados {@link Invoice} y
 * {@link CreditNote} (ADR 0009). Es una foto calculada a partir de la factura y de TODAS sus
 * notas crédito; Java puro, sin estado propio que persistir.
 *
 * <p>La regla se expresa en <b>cantidades por línea</b>: lo acreditado de cada línea, sumando las
 * notas PENDING e ISSUED (las REJECTED no cuentan), nunca supera lo facturado. Las PENDING cuentan
 * porque aún pueden emitirse.
 *
 * <p>Esta clase solo calcula. Que la foto siga vigente al guardar una nota nueva (otra petición
 * pudo crear una nota entre medio) lo garantiza el bloqueo optimista sobre la factura en la
 * persistencia (ADR 0009, decisión 3).
 *
 * <p>Los montos son informativos. {@link #remainingValue()} es el valor de las cantidades que
 * aún se pueden acreditar, con el redondeo por línea de siempre; por ese redondeo puede diferir
 * en centavos de "total facturado - total acreditado" cuando una línea se acredita en varias notas.
 */
public final class CreditBalance {

    /** Situación de una línea de la factura. Las cantidades tienen escala 2. */
    public record LineBalance(int lineNumber, Quantity invoiced, BigDecimal credited, BigDecimal remaining) {

        public boolean hasRemaining() {
            return remaining.signum() > 0;
        }
    }

    private final Invoice invoice;
    private final List<CreditNote> creditNotes;

    private CreditBalance(Invoice invoice, List<CreditNote> creditNotes) {
        this.invoice = invoice;
        this.creditNotes = creditNotes;
    }

    /**
     * @param creditNotes todas las notas de la factura, en cualquier estado
     * @throws InvalidCreditNoteException si alguna nota es de otra factura
     */
    public static CreditBalance of(Invoice invoice, List<CreditNote> creditNotes) {
        if (invoice == null) {
            throw InvalidCreditNoteException.missing("la factura que acredita");
        }
        if (creditNotes == null || creditNotes.stream().anyMatch(Objects::isNull)) {
            throw InvalidCreditNoteException.missing("la lista de notas crédito de la factura (vacía si no hay)");
        }
        for (CreditNote note : creditNotes) {
            if (!note.invoiceId().equals(invoice.id())) {
                throw InvalidCreditNoteException.foreignCreditNote(note.id().value(), invoice.id().value());
            }
        }
        return new CreditBalance(invoice, List.copyOf(creditNotes));
    }

    public Invoice invoice() {
        return invoice;
    }

    /** Una entrada por línea de la factura, en su orden. */
    public List<LineBalance> lines() {
        return IntStream.rangeClosed(1, invoice.lines().size()).mapToObj(this::line).toList();
    }

    /** @throws InvalidCreditNoteException si la factura no tiene esa línea */
    public LineBalance line(int lineNumber) {
        InvoiceLine invoiceLine = invoiceLine(lineNumber);
        BigDecimal credited = creditNotes.stream()
                .filter(note -> note.status().countsTowardsBalance())
                .flatMap(note -> note.lines().stream())
                .filter(line -> line.invoiceLineNumber() == lineNumber)
                .map(line -> line.quantity().value())
                .reduce(BigDecimal.ZERO.setScale(Quantity.MAX_DECIMALS), BigDecimal::add);
        return new LineBalance(lineNumber, invoiceLine.quantity(), credited,
                invoiceLine.quantity().value().subtract(credited));
    }

    /** La línea de la factura con ese número (desde 1). */
    public InvoiceLine invoiceLine(int lineNumber) {
        if (lineNumber < 1 || lineNumber > invoice.lines().size()) {
            throw InvalidCreditNoteException.unknownLine(lineNumber, invoice.lines().size());
        }
        return invoice.lines().get(lineNumber - 1);
    }

    public boolean hasRemaining() {
        return lines().stream().anyMatch(LineBalance::hasRemaining);
    }

    /** Total de las notas ISSUED: lo acreditado con validez ante la DIAN. */
    public Money issuedCreditTotal() {
        return totalOf(note -> note.status() == CreditNoteStatus.ISSUED);
    }

    /** Total de las notas PENDING: reservado, todavía sin emitir. */
    public Money pendingCreditTotal() {
        return totalOf(note -> note.status() == CreditNoteStatus.PENDING);
    }

    /** Valor de las cantidades que aún se pueden acreditar (el "saldo" de la factura). */
    public Money remainingValue() {
        return lines().stream()
                .filter(LineBalance::hasRemaining)
                .map(balance -> invoiceLine(balance.lineNumber()).withQuantity(new Quantity(balance.remaining()))
                        .total())
                .reduce(Money.ZERO, Money::plus);
    }

    private Money totalOf(Predicate<CreditNote> filter) {
        return creditNotes.stream().filter(filter).map(CreditNote::total).reduce(Money.ZERO, Money::plus);
    }
}
