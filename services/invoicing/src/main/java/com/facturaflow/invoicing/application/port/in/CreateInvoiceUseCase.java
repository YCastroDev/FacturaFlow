package com.facturaflow.invoicing.application.port.in;

import com.facturaflow.invoicing.application.exception.ReferenceCodeConflictException;
import com.facturaflow.invoicing.domain.exception.DomainException;

/**
 * Crea una factura PENDING. Es idempotente por código de referencia: repetir la misma
 * petición devuelve la factura existente sin crear otra.
 */
public interface CreateInvoiceUseCase {

    /**
     * @throws DomainException si los datos violan una regla de negocio
     * @throws ReferenceCodeConflictException si el código ya se usó con un contenido distinto
     */
    CreateInvoiceResult create(CreateInvoiceCommand command);
}
