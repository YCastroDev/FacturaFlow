package com.facturaflow.invoicing.application.port.in;

import com.facturaflow.invoicing.application.exception.CreditNoteReferenceCodeConflictException;
import com.facturaflow.invoicing.application.exception.InvoiceConcurrentModificationException;
import com.facturaflow.invoicing.application.exception.InvoiceNotFoundException;
import com.facturaflow.invoicing.domain.exception.DomainException;
import com.facturaflow.invoicing.domain.exception.InvoiceNotCreditableException;

/**
 * Crea una nota crédito PENDING sobre una factura ISSUED (ADR 0009). Es idempotente por código de
 * referencia, como la creación de facturas: repetir la misma petición devuelve la nota existente.
 */
public interface CreateCreditNoteUseCase {

    /**
     * @throws InvoiceNotFoundException si la factura no existe
     * @throws InvoiceNotCreditableException si la factura no está ISSUED
     * @throws DomainException si viola una regla de negocio (p. ej. acreditar más de lo facturado)
     * @throws CreditNoteReferenceCodeConflictException si el código ya se usó con un contenido distinto
     * @throws InvoiceConcurrentModificationException si otras notas simultáneas sobre la misma
     *         factura impidieron guardarla tras varios intentos
     */
    CreateCreditNoteResult create(CreateCreditNoteCommand command);
}
