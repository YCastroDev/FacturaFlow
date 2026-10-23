package com.facturaflow.invoicing.domain.exception;

import java.math.BigDecimal;
import java.util.UUID;

public final class InvalidCreditNoteException extends DomainException {

    private InvalidCreditNoteException(String message) {
        super(message);
    }

    public static InvalidCreditNoteException missing(String field) {
        return new InvalidCreditNoteException("La nota crédito requiere " + field + ".");
    }

    public static InvalidCreditNoteException invalidLineNumber(int lineNumber) {
        return new InvalidCreditNoteException("El número de línea debe ser 1 o mayor: " + lineNumber + ".");
    }

    public static InvalidCreditNoteException noLines() {
        return new InvalidCreditNoteException("Una devolución parcial debe acreditar al menos una línea.");
    }

    public static InvalidCreditNoteException annulmentWithLines() {
        return new InvalidCreditNoteException("Una anulación total acredita todo lo pendiente de la factura: "
                + "no lleva líneas.");
    }

    public static InvalidCreditNoteException duplicateLine(int lineNumber) {
        return new InvalidCreditNoteException("La línea " + lineNumber + " aparece más de una vez en la nota.");
    }

    public static InvalidCreditNoteException unknownLine(int lineNumber, int invoiceLines) {
        return new InvalidCreditNoteException("La factura no tiene línea " + lineNumber + " (tiene "
                + invoiceLines + ").");
    }

    public static InvalidCreditNoteException exceedsRemaining(int lineNumber, BigDecimal requested,
                                                              BigDecimal remaining) {
        return new InvalidCreditNoteException("La línea " + lineNumber + " no admite acreditar "
                + requested.toPlainString() + ": solo quedan " + remaining.toPlainString()
                + " por acreditar (lo acreditado nunca puede superar lo facturado).");
    }

    public static InvalidCreditNoteException nothingToCredit(UUID invoiceId) {
        return new InvalidCreditNoteException("La factura " + invoiceId + " ya no tiene cantidades por acreditar.");
    }

    public static InvalidCreditNoteException issueDateBeforeInvoice(String issueDate, String invoiceIssueDate) {
        return new InvalidCreditNoteException("La nota crédito no puede tener una fecha (" + issueDate
                + ") anterior a la de su factura (" + invoiceIssueDate + ").");
    }

    public static InvalidCreditNoteException foreignCreditNote(UUID creditNoteId, UUID invoiceId) {
        return new InvalidCreditNoteException("La nota crédito " + creditNoteId + " no pertenece a la factura "
                + invoiceId + ".");
    }

    public static InvalidCreditNoteException observationTooLong(int length, int maxLength) {
        return new InvalidCreditNoteException("La observación admite máximo " + maxLength + " caracteres (tiene "
                + length + ").");
    }

    public static InvalidCreditNoteException inconsistentState(String status, String detail) {
        return new InvalidCreditNoteException("Estado incoherente: una nota crédito " + status + " " + detail + ".");
    }

    public static InvalidCreditNoteException invalidVersion(long version) {
        return new InvalidCreditNoteException("La versión de una nota crédito no puede ser negativa: " + version
                + ".");
    }

    public static InvalidCreditNoteException missingRejectionReason() {
        return new InvalidCreditNoteException("El rechazo de una nota crédito requiere un motivo.");
    }

    public static InvalidCreditNoteException invalidTransition(UUID creditNoteId, String current, String target) {
        return new InvalidCreditNoteException("La nota crédito " + creditNoteId + " está en estado " + current
                + " y no puede pasar a " + target + ". Solo una nota PENDING puede emitirse o rechazarse.");
    }
}
