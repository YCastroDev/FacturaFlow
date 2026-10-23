package com.facturaflow.invoicing.application.exception;

import java.util.UUID;

/**
 * Falla TÉCNICA del proveedor de facturación electrónica (caído, timeout, respuesta
 * ilegible, límite de peticiones). No dice nada sobre el documento (factura o nota crédito):
 * sigue en su estado y la operación (emitir, descargar el PDF) se puede reintentar. El mensaje
 * es para los logs; nunca se expone al cliente.
 */
public final class ElectronicInvoiceProviderUnavailableException extends RuntimeException {

    /** Tipo de documento afectado; da el texto del mensaje al cliente. */
    public enum Document {
        INVOICE("factura", "La factura"),
        CREDIT_NOTE("nota crédito", "La nota crédito");

        private final String name;
        private final String subject;

        Document(String name, String subject) {
            this.name = name;
            this.subject = subject;
        }

        /** Sujeto de una frase: "La factura", "La nota crédito". */
        public String subject() {
            return subject;
        }
    }

    private final Document document;
    private final UUID documentId;

    /** Falla al operar sobre una factura. */
    public ElectronicInvoiceProviderUnavailableException(UUID invoiceId, String detail, Throwable cause) {
        this(Document.INVOICE, invoiceId, detail, cause);
    }

    private ElectronicInvoiceProviderUnavailableException(Document document, UUID documentId, String detail,
                                                          Throwable cause) {
        super("El proveedor de facturación electrónica no está disponible (" + document.name + " " + documentId
                + "): " + detail, cause);
        this.document = document;
        this.documentId = documentId;
    }

    /** Falla al operar sobre una nota crédito (ADR 0009). */
    public static ElectronicInvoiceProviderUnavailableException forCreditNote(UUID creditNoteId, String detail,
                                                                             Throwable cause) {
        return new ElectronicInvoiceProviderUnavailableException(Document.CREDIT_NOTE, creditNoteId, detail, cause);
    }

    public Document document() {
        return document;
    }

    public UUID documentId() {
        return documentId;
    }
}
