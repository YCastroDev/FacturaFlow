package com.facturaflow.invoicing.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositorio Spring Data, interno al adaptador. Como en las facturas, los métodos
 * {@code findWithLines...} traen nota, líneas e impuestos en UNA consulta (sin N+1).
 */
interface CreditNoteJpaRepository extends JpaRepository<CreditNoteEntity, UUID> {

    @EntityGraph(attributePaths = {"lines", "lines.taxes"})
    Optional<CreditNoteEntity> findWithLinesById(UUID id);

    @EntityGraph(attributePaths = {"lines", "lines.taxes"})
    Optional<CreditNoteEntity> findWithLinesByReferenceCode(String referenceCode);

    /** Todas las notas de una factura con sus líneas, en una consulta, en orden de creación. */
    @EntityGraph(attributePaths = {"lines", "lines.taxes"})
    List<CreditNoteEntity> findWithLinesByInvoiceIdOrderByCreatedAtAscIdAsc(UUID invoiceId);
}
