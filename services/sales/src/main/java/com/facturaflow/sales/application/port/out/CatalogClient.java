package com.facturaflow.sales.application.port.out;

import com.facturaflow.sales.domain.model.CustomerDocumentRef;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Puerto de salida hacia catalog, dueño de clientes y productos (ADR 0012). Comunicación síncrona.
 * "No existe" no es una falla: es un {@code Optional} vacío o un código que no viene en la lista.
 */
public interface CatalogClient {

    /**
     * @throws com.facturaflow.sales.application.exception.CatalogUnavailableException si catalog no responde o
     *         el circuito está abierto
     */
    Optional<CatalogCustomer> findCustomer(CustomerDocumentRef document);

    /**
     * Los productos que existen con esos códigos (sin distinguir mayúsculas), activos o no, en UNA llamada.
     *
     * @param codes de 1 a {@code Sale.MAX_LINES} códigos distintos
     * @throws com.facturaflow.sales.application.exception.CatalogUnavailableException si catalog no responde o
     *         el circuito está abierto
     */
    List<CatalogProduct> findProducts(Set<String> codes);
}
