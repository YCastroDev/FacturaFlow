package com.facturaflow.sales.application.port.in;

public interface CreateSaleUseCase {

    /**
     * @throws com.facturaflow.sales.application.exception.ReferenceCodeConflictException el código ya existe con
     *         otro contenido
     * @throws com.facturaflow.sales.application.exception.InvalidCatalogReferencesException cliente o productos
     *         inexistentes o inactivos en catalog
     * @throws com.facturaflow.sales.application.exception.CatalogUnavailableException catalog no responde
     */
    CreateSaleResult create(CreateSaleCommand command);
}
