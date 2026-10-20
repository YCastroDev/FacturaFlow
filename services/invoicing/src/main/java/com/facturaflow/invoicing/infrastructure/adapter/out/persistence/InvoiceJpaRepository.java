package com.facturaflow.invoicing.infrastructure.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositorio Spring Data, interno al adaptador. Los métodos {@code findWithLines...}
 * usan un entity graph: factura, líneas e impuestos en UNA consulta (sin N+1).
 */
interface InvoiceJpaRepository extends JpaRepository<InvoiceEntity, UUID> {

    @EntityGraph(attributePaths = {"lines", "lines.taxes"})
    Optional<InvoiceEntity> findWithLinesById(UUID id);

    @EntityGraph(attributePaths = {"lines", "lines.taxes"})
    Optional<InvoiceEntity> findWithLinesByReferenceCode(String referenceCode);
}
