package com.facturaflow.invoicing.application.port.out;

import com.facturaflow.invoicing.application.exception.ElectronicInvoiceProviderUnavailableException;
import com.facturaflow.invoicing.domain.model.Invoice;

/**
 * Puerto de salida hacia el proveedor tecnológico que valida la factura ante la DIAN
 * (Factus, o un proveedor falso en desarrollo). Las implementaciones traducen el dominio
 * a los catálogos del proveedor (ADR 0004); el caso de uso no conoce ninguno.
 */
public interface ElectronicInvoiceProvider {

    /**
     * Solicita la emisión de una factura PENDING. No modifica la factura: el caso de uso
     * aplica el resultado al agregado.
     *
     * @throws ElectronicInvoiceProviderUnavailableException si el proveedor no respondió
     *         (caído, timeout, respuesta ilegible); la operación se puede reintentar
     */
    IssuanceResult issue(Invoice invoice);
}
